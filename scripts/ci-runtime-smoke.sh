#!/usr/bin/env bash
#
# Dargent runtime-smoke CI job (E12 S2+S3). One script = one job contract (deploy-smoke-e12-spec.md §3).
# Career: github runner (ubuntu-latest, docker) — also runnable locally against the compose stack.
#
# Phases:
#   P0 ensure compose stack (images pre-built as dargent-api:compose / dargent-psp-simulator:compose)
#   P1 readiness wait on the management port (9090) INSIDE each api color — never :8080 (Q25)
#   P2 insert a job-scoped API key (Q2: psp_test_ prefix, SHA-256 hash, direct DB insert, rerun-safe)
#   P3 scripts/smoke.sh — create → replay → pay → CONFIRMED (S2 core)
#   P4 chaos leg — webhooks suppressed at nginx; payment must self-heal via the reconciler (S3)
#   P5 shutdown-under-load — SIGTERM the active color while probing; zero connection-refused (S3)
#   P6 restore the fleet to healthy
# FAILURE = logs printed + exit 1 (workflow uploads them as an artifact).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
NGINX_RUNTIME_CONF="${DARGENT_NGINX_RUNTIME:-$REPO_DIR/deploy/runtime/nginx.conf}"
API_BASE="${SMOKE_API_BASE:-http://localhost:8080}"
PSP_BASE="${SMOKE_PSP_BASE:-http://localhost:8090}"
# Create schedules the first reconcile at now + first backoff rung (default DARGENT_RECONCILER_BACKOFF_MS
# rung 1 = 60s). The scan interval only gates how often runOnce checks; confirmation lands ~first rung.
# Budget = first rung (60s) + scan slack (2s) + CI margin → 90s.
RECONCILE_DEADLINE_S="${RECONCILE_DEADLINE_S:-90}"
SHUTDOWN_BUDGET_S=30

note() { echo "RUNTIME-SMOKE $(date +%T): $*"; }
fail() { echo "RUNTIME-SMOKE FAIL: $*" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# The runtime conf is bind-mounted as a DIRECTORY (docker/compose.yaml) so host writes resolve by name
# on reload; a single-file bind would pin the inode and reloads would read stale config (E12 S2).
nginx_reload() { compose exec -T nginx nginx -t >/dev/null && compose exec -T nginx nginx -s reload; }

# Idempotent removal of every chaos-suppression location (in place, temp-safe — never awk on the same path).
strip_chaos_blocks() {
    awk '!/# chaos suppression/{print}' "$NGINX_RUNTIME_CONF" > "$NGINX_RUNTIME_CONF.__tmp"
    cat "$NGINX_RUNTIME_CONF.__tmp" > "$NGINX_RUNTIME_CONF" && rm -f "$NGINX_RUNTIME_CONF.__tmp"
    grep -q '^events {' "$NGINX_RUNTIME_CONF"
}

# P4 EXIT trap: a failed P4 must NEVER leak a blocked webhook path into the runtime stack.
chaos_cleanup() {
    if grep -q '# chaos suppression' "$NGINX_RUNTIME_CONF" 2>/dev/null; then
        strip_chaos_blocks && nginx_reload >/dev/null 2>&1 || true
    fi
}
trap chaos_cleanup EXIT

# ------------------------------------------------------------------ P0: stack up + prerequisites
for img in dargent-api:compose dargent-psp-simulator:compose; do
    docker image inspect "$img" >/dev/null 2>&1 || fail "image not found: $img (build it first)"
done
[[ -f "$NGINX_RUNTIME_CONF" ]] || "$SCRIPT_DIR/deploy.sh" --init
compose up -d
"$SCRIPT_DIR/deploy.sh" --wait-ready api-blue  >/dev/null
"$SCRIPT_DIR/deploy.sh" --wait-ready api-green >/dev/null
note "P0 stack up — api-blue + api-green ready on :9090"

# ------------------------------------------------------------------ P1: readiness via management port
note "P1 readiness ok (compose healthcheck + management-port wait for both colors)"

# ------------------------------------------------------------------ P2: job-scoped API key (rerun-safe)
SMOKE_KEY=$(python3 -c 'import secrets,string; a=string.digits+string.ascii_uppercase+string.ascii_lowercase; print("psp_test_"+"".join(secrets.choice(a) for _ in range(43)))')
[[ "${#SMOKE_KEY}" -eq 52 ]] || fail "P2: generated key length ${#SMOKE_KEY} != 52"
KEY_HASH=$(printf '%s' "$SMOKE_KEY" | sha256sum | cut -d' ' -f1)
compose exec -T postgres psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q \
    -c "delete from payments.api_keys where key_prefix = 'psp_test_'" \
    -c "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at) values (gen_random_uuid(), 'a0000000-0000-4000-8000-000000000001', 'ci-runtime-smoke', 'psp_test_', '${KEY_HASH}', now())"
note "P2 key inserted (prefix psp_test_)"

# ------------------------------------------------------------------ P3: smoke.sh core legs
"$SCRIPT_DIR/smoke.sh" "$API_BASE" "$SMOKE_KEY" "$PSP_BASE" || fail "P3: smoke core legs failed"
note "P3 smoke PASS (create → replay → pay → CONFIRMED)"

# ------------------------------------------------------------------ P4: chaos — webhook suppressed, reconciler self-heals
note "P4 chaos leg — suppressing /webhooks/psp at nginx"
CHAOS_BODY='{"amount":200,"description":"chaos webhook-drop"}'
KEY_CHAOS="chaos-$(date +%s%N)"
CHAOS_CREATE=$(command curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer ${SMOKE_KEY}" \
    -H "Idempotency-Key: ${KEY_CHAOS}" -H "Content-Type: application/json" \
    -d "$CHAOS_BODY" "$API_BASE/v1/payments")
CHAOS_TXID=$(grep -o '"txid":"[^"]*"' <<<"${CHAOS_CREATE%$'\n'*}" | head -1 | cut -d'"' -f4)
[[ -n "$CHAOS_TXID" ]] || fail "P4: chaos create failed: ${CHAOS_CREATE%$'\n'*}"
# Purge stale chaos locations, then insert one, and reload. nginx reload is async; poll POST
# /webhooks/psp until it returns 503 (fail-closed). Any failure path lifts the block via the EXIT trap.
# In-place edit note: `awk ... > $NGINX_RUNTIME_CONF` truncates the target BEFORE awk reads it, so edits
# MUST land in a temp file and be copied over (see lesson: in-place truncate zeroes the file silently).
awk '!/# chaos suppression/{print}' "$NGINX_RUNTIME_CONF" \
    | awk '/listen 80;/{print; print "    location /webhooks/psp { return 503; }    # chaos suppression (E12 S3)"; next}{print}' \
    > "$NGINX_RUNTIME_CONF.__tmp"
cat "$NGINX_RUNTIME_CONF.__tmp" > "$NGINX_RUNTIME_CONF" && rm -f "$NGINX_RUNTIME_CONF.__tmp"
grep -q '^events {' "$NGINX_RUNTIME_CONF" || fail "P4: runtime conf missing events section after edit"
nginx_reload
DEADLINE=$(( $(date +%s) + 30 ))
BLOCKED=""
while (( $(date +%s) < DEADLINE )); do
    sleep 1
    BLOCKED=$(command curl -sS -o /dev/null -w '%{http_code}' -X POST "$API_BASE/webhooks/psp" -d '{}' 2>/dev/null || true)
    [[ "$BLOCKED" == "503" ]] && break
done
if [[ "$BLOCKED" != "503" ]]; then
    strip_chaos_blocks && nginx_reload >/dev/null 2>&1 || true
    fail "P4: webhook block failed to apply within 30s (last code $BLOCKED)"
fi
note "P4 webhooks blocked at nginx (confirmed: POST /webhooks/psp -> 503)"
PAY_HTTP=$(command curl -sS -o /dev/null -w '%{http_code}' -X POST "$PSP_BASE/cobs/$CHAOS_TXID/payments")
[[ "$PAY_HTTP" == "200" ]] || fail "P4: simulator pay expected 200, got $PAY_HTTP"
DEADLINE=$(( $(date +%s) + RECONCILE_DEADLINE_S ))
FINAL=""
while (( $(date +%s) < DEADLINE )); do
    FINAL=$(command curl -sS -H "Authorization: Bearer ${SMOKE_KEY}" "$API_BASE/v1/payments/$CHAOS_TXID" 2>/dev/null || true)
    grep -q '"status":"CONFIRMED"' <<<"$FINAL" && break
    sleep 1
done
[[ "$FINAL" == *'"status":"CONFIRMED"'* ]] || fail "P4: reconciler did NOT confirm within ${RECONCILE_DEADLINE_S}s (last: $FINAL)"
# The webhook path was blocked the whole window: a zero row count proves the reconciler did it alone.
WEBHOOK_ROWS=$(compose exec -T postgres psql -U dargent -d dargent -Atc \
    "select count(*) from payments.webhook_events where txid = '${CHAOS_TXID}'")
[[ "$WEBHOOK_ROWS" == "0" ]] || fail "P4: webhook_events has ${WEBHOOK_ROWS} rows for the blocked txid — suppression leaked"
# Lift the block idempotently (purge any markers) and poll until the intake answers again.
strip_chaos_blocks || fail "P4: runtime conf missing events section after lift"
nginx_reload
DEADLINE=$(( $(date +%s) + 30 ))
while (( $(date +%s) < DEADLINE )); do
    sleep 1
    BLOCKED=$(command curl -sS -o /dev/null -w '%{http_code}' -X POST "$API_BASE/webhooks/psp" -d '{}' 2>/dev/null || true)
    [[ "$BLOCKED" != "503" ]] && break
done
[[ "$BLOCKED" == "503" ]] && fail "P4: webhook block did not lift after restore (proxy still 503)"
note "P4 ok — ${CHAOS_TXID} CONFIRMED by the reconciler, zero webhook rows (self-healing without the webhook)"

# ------------------------------------------------------------------ P5: shutdown-under-load
note "P5 shutdown-under-load — SIGTERM active color with a probe loop"
ACTIVE=$("$SCRIPT_DIR/deploy.sh" --active)
[[ "$ACTIVE" == "api-blue" || "$ACTIVE" == "api-green" ]] || fail "P5: cannot determine active color ($ACTIVE)"
PROBE_LOG=$(mktemp)
( for i in $(seq 1 40); do
      code=$(command curl -sS -o /tmp/shutdown.probe.body -w '%{http_code}' \
          -H "Authorization: Bearer ${SMOKE_KEY}" "$API_BASE/v1/payments/$CHAOS_TXID" 2>/dev/null || echo "REFUSED")
      echo "$code" >> "$PROBE_LOG"
      cp /tmp/shutdown.probe.body /tmp/shutdown.probe.body.$i 2>/dev/null || true
      sleep 1
  done ) &
PROBE_PID=$!
sleep 3
START_S=$SECONDS
compose stop -t "$SHUTDOWN_BUDGET_S" "$ACTIVE"
STOP_ELAPSED=$(( SECONDS - START_S ))
wait "$PROBE_PID"
[[ "$STOP_ELAPSED" -le "$SHUTDOWN_BUDGET_S" ]] || fail "P5: active color took ${STOP_ELAPSED}s to stop (budget ${SHUTDOWN_BUDGET_S}s)"
if grep -q "REFUSED" "$PROBE_LOG"; then fail "P5: connection-refused observed during drain (nginx down)"; fi
SUMMARY=$(sort "$PROBE_LOG" | uniq -c | tr '\n' ' ')
note "P5 ok — stop in ${STOP_ELAPSED}s, probe codes during drain: ${SUMMARY}"
JSON_HITS=0
for f in /tmp/shutdown.probe.body.*; do
    [[ -f "$f" ]] || continue
    if grep -q '"txid"' "$f"; then
        JSON_HITS=$((JSON_HITS + 1))
        grep -q "$CHAOS_TXID" "$f" || fail "P5: inconsistent GET body during drain: $(cat "$f")"
        grep -qE '"status":"(PENDING|CONFIRMED)"' "$f" || fail "P5: unexpected status in body during drain: $(cat "$f")"
    fi
done
[[ "$JSON_HITS" -ge 1 ]] || fail "P5: no API JSON response observed during drain (${SUMMARY}) — gateway swallowed everything"
note "P5 ok — ${JSON_HITS} consistent API responses during drain (others are nginx gateway artifacts)"

# ------------------------------------------------------------------ P6: restore + summary
compose start "$ACTIVE" >/dev/null
"$SCRIPT_DIR/deploy.sh" --wait-ready "$ACTIVE" >/dev/null
note "P6 fleet restored — $ACTIVE back, both colors ready"
note "RUNTIME-SMOKE PASS (P0-P6)"
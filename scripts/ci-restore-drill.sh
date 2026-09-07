#!/usr/bin/env bash
#
# Dargent restore drill (E14 S4 — runbook §6: "the drill is the deliverable").
# CI job body (`restore-drill`): boot seeded stack → money path (deterministic txns via the
# smoke path) → backup.sh → DESTROY THE CLUSTER (down -v) → restore.sh --fresh → assertions green.
# The restore+verify section is wall-clocked; the measured RTO is printed and compared to ≤ 30 min.
#
# This is the first repo job that destroys a database cluster on purpose: it runs on its own
# compose project (isolated -p dargent-drill) so it can never touch a developer's running stack.
#
# FAILURE = logs + manifests uploaded as artifacts (see the workflow job) + exit 1.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
API_BASE="${SMOKE_API_BASE:-http://localhost:8080}"
PSP_BASE="${SMOKE_PSP_BASE:-http://localhost:8090}"
BACKUP_DIR="${DARGENT_DRILL_BACKUP_DIR:-/tmp/restore-drill}"
RTO_BUDGET_S="${RTO_BUDGET_S:-1800}" # runbook §6: RTO ≤ 30 min, measured

note() { echo "DRILL $(date +%T): $*"; }
fail() { echo "DRILL FAIL: $*" >&2; exit 1; }

# Isolation (first repo job that destroys a cluster ON PURPOSE): the drill runs on its own
# compose project AND its own ports — a developer stack may be running on the defaults. The
# override remaps every published port (host side) to 18xxx; service names/links are untouched
# (containers talk over the compose network, not the published ports). COMPOSE_PROJECT_NAME
# overrides the compose file's `name: dargent` for every nested `docker compose` call in
# backup.sh / restore.sh / deploy.sh — the developer's stack and its pgdata volume are
# untouched; `down -v` destroys only dargent-drill's volume.
export COMPOSE_PROJECT_NAME=dargent-drill
# Drill port map (host side only; containers talk over the compose network by service name).
# Explicit ports — no arithmetic, no collisions with the dev stack defaults (5432/4566/8080-8090).
DRILL_NGINX_PORT="${DRILL_NGINX_PORT:-18080}"
DRILL_PSP_PORT="${DRILL_PSP_PORT:-18090}"
OVERRIDE="$(mktemp -d)/drill-ports.yaml"
cat > "$OVERRIDE" <<EOF
services:
  postgres:
    ports: !override
      - "18032:5432"
  localstack:
    ports: !override
      - "18066:4566"
  api-blue:
    ports: !override
      - "18081:8080"
  api-green:
    ports: !override
      - "18082:8080"
  psp-simulator:
    ports: !override
      - "$DRILL_PSP_PORT:8090"
  nginx:
    ports: !override
      - "$DRILL_NGINX_PORT:80"
EOF
COMPOSE_FILES="-f $COMPOSE_FILE -f $OVERRIDE"
compose() { docker compose $COMPOSE_FILES "$@"; }
# The money path probes the drill's published ports (nginx front + psp simulator front).
API_BASE="${SMOKE_API_BASE:-http://localhost:$DRILL_NGINX_PORT}"
PSP_BASE="${SMOKE_PSP_BASE:-http://localhost:$DRILL_PSP_PORT}"
export COMPOSE_FILE="$COMPOSE_FILE" # nested scripts see the base file; the env var carries only
# the project isolation (COMPOSE_PROJECT_NAME). The PORT OVERRIDE flows to backup.sh/restore.sh
# via DARGENT_COMPOSE_OVERRIDE so a dev stack can keep the default ports while the drill runs.
export DARGENT_COMPOSE_OVERRIDE="$OVERRIDE"

for img in dargent-api:compose dargent-psp-simulator:compose; do
    docker image inspect "$img" >/dev/null 2>&1 || fail "image not found: $img (build it first)"
done
[[ -f "$REPO_DIR/deploy/runtime/nginx.conf" ]] || "$SCRIPT_DIR/deploy.sh" --init

# The event spine stays OFF for the drill (default compose): the drill proves backup/restore,
# not event delivery. Webhook confirmation (smoke leg 4) is the money path.
export DARGENT_RELAY_ENABLED=false
export DARGENT_LEDGER_CONSUMER_ENABLED=false
export DARGENT_RECONCILER_ENABLED=true
export DARGENT_RECONCILER_SCAN_MS=2000

# ------------------------------------------------------------------ D1: seeded stack + money path
SMOKE_KEY=$(python3 -c 'import secrets,string; a=string.digits+string.ascii_uppercase+string.ascii_lowercase; print("psp_test_"+"".join(secrets.choice(a) for _ in range(43)))')
[[ "${#SMOKE_KEY}" -eq 52 ]] || fail "generated key length ${#SMOKE_KEY} != 52"

note "D1 stack up (isolated project dargent-drill)"
compose up -d >/dev/null
"$SCRIPT_DIR/deploy.sh" --wait-ready api-blue >/dev/null

KEY_HASH=$(printf '%s' "$SMOKE_KEY" | sha256sum | cut -d' ' -f1)
compose exec -T postgres psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q \
    -c "delete from payments.api_keys where key_prefix = 'psp_test_'" \
    -c "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at) values (gen_random_uuid(), 'a0000000-0000-4000-8000-000000000001', 'ci-restore-drill', 'psp_test_', '${KEY_HASH}', now())"

TXN=1
smoke_txn() {
    OUT=$( "$SCRIPT_DIR/smoke.sh" "$API_BASE" "$SMOKE_KEY" "$PSP_BASE" 2>&1 ) \
        || { printf '%s\n' "$OUT" >&2; fail "D1: smoke money path txn #$TXN failed"; }
    TXID=$(grep -o 'txid=[A-Z0-9]*' <<<"$OUT" | head -1 | cut -d= -f2)
    [[ -n "$TXID" ]] || fail "D1: could not extract txid from smoke output (txn #$TXN)"
    note "D1 txn $TXN/$1 CONFIRMED — txid=$TXID"
    TXN=$((TXN+1))
}
N_TXNS="${DRILL_TXNS:-3}"
for i in $(seq 1 "$N_TXNS"); do smoke_txn "$N_TXNS"; done
note "D1 money path ok — $N_TXNS deterministic txns CONFIRMED"

# ------------------------------------------------------------------ D2: backup (dump + manifest)
export DARGENT_BACKUP_DIR="$BACKUP_DIR"
"$SCRIPT_DIR/backup.sh" || fail "D2: backup.sh failed"
DUMP=$(ls -1t "$BACKUP_DIR"/dargent-*.dump | head -1)
MANIFEST="${DUMP%.dump}.manifest.json"
[[ -f "$MANIFEST" ]] || fail "D2: manifest missing next to dump"
note "D2 backup ok — $(basename "$DUMP") ($(stat -c %s "$DUMP") bytes), manifest: $(python3 -c "import json; m=json.load(open('$MANIFEST')); print('payments=' + str(m['tables']['payments.payments']), 'ΣDR=' + str(m['ledgerSums']['debitCents']), 'ΣCR=' + str(m['ledgerSums']['creditCents']))")"

# ------------------------------------------------------------------ D3: DESTROY the cluster
note "D3 destroying the cluster (down -v — volume gone, this is the disaster)"
compose down -v --remove-orphans >/dev/null 2>&1 || true
compose ps -q postgres | grep -q . && fail "D3: postgres still running after down -v"
note "D3 cluster destroyed"

# ------------------------------------------------------------------ D4: restore + verify (WALL-CLOCKED)
RTO_START=$(date +%s)
"$SCRIPT_DIR/restore.sh" "$DUMP" --fresh || fail "D4: restore.sh failed — restore UNVERIFIED"
RTO_SECONDS=$(( $(date +%s) - RTO_START ))
(( RTO_SECONDS <= RTO_BUDGET_S )) || fail "D4: measured RTO ${RTO_SECONDS}s exceeds the stated budget ${RTO_BUDGET_S}s (runbook §6: ≤ 30 min)"
note "D4 restore VERIFIED — measured RTO ${RTO_SECONDS}s (budget ${RTO_BUDGET_S}s)"

# ------------------------------------------------------------------ D5: post-restore money path (data serves)
# The restored stack must serve NEW traffic: one more smoke txn proves the restored cluster
# is a working system, not just matching counts. restore.sh boots postgres+api-blue only;
# the money path needs the full stack (nginx front + psp-simulator). The nginx upstream uses
# `resolve` (Docker DNS) — right after up it can 502 while the first resolution lands, so the
# front is probed until it answers (401 = auth-gated health, i.e. proxying fine).
compose up -d >/dev/null
# BOTH colors must be ready before the front: nginx load-balances blue+green, and an upstream
# whose DNS has not resolved yet (resolver valid=10s) 502s the request that lands on it.
"$SCRIPT_DIR/deploy.sh" --wait-ready api-blue >/dev/null
"$SCRIPT_DIR/deploy.sh" --wait-ready api-green >/dev/null
FRONT_READY=""
for _ in $(seq 1 30); do
    CODE=$(command curl -s -o /dev/null -w '%{http_code}' "$API_BASE/actuator/health" 2>/dev/null || true)
    [[ "$CODE" != "502" && "$CODE" != "504" && -n "$CODE" ]] && { FRONT_READY="$CODE"; break; }
    sleep 2
done
[[ -n "$FRONT_READY" ]] || fail "D5: nginx front did not come back after restore (last code: $CODE)"
note "D5 front ready (health via front: $FRONT_READY)"
KEY_HASH=$(printf '%s' "$SMOKE_KEY" | sha256sum | cut -d' ' -f1)
compose exec -T postgres psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q \
    -c "delete from payments.api_keys where key_prefix = 'psp_test_'" \
    -c "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at) values (gen_random_uuid(), 'a0000000-0000-4000-8000-000000000001', 'ci-restore-drill', 'psp_test_', '${KEY_HASH}', now())"
"$SCRIPT_DIR/deploy.sh" --wait-ready api-blue >/dev/null
TXN=1; smoke_txn 1
note "D5 post-restore money path ok — restored cluster serves NEW traffic (txid=$TXID)"

# ------------------------------------------------------------------ D6: teardown
compose down -v --remove-orphans >/dev/null 2>&1 || true
note "D6 teardown ok"
echo "DRILL RESULT: PASS — $N_TXNS txns seeded, backup→destroy→restore verified, RTO ${RTO_SECONDS}s (≤ ${RTO_BUDGET_S}s), post-restore txn CONFIRMED"

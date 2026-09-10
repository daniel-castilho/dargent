#!/usr/bin/env bash
#
# Dargent k6 HARD-GATE harness (M5 S3, D4). Career: CI job (ubuntu-latest) — runnable locally
# against the compose stack (scripts/load/k6-gate.js carries the thresholds; this script owns
# the environment).
#
# Legs:
#   G1 stack up (base compose — spine OFF, the smoke topology) + readiness on both colors
#   G2 job-scoped API key (psp_test_ prefix, SHA-256 hash, direct DB insert, rerun-safe)
#   G3 k6 gate GREEN — create p95<75ms, 0 errors (scripts/load/k6-gate.js)
#   G4 BITE-PROOF — the same gate with an impossible threshold (p95<1ms) MUST exit red.
#      A gate that cannot be shown biting is not a gate (m5-backlog §S3.3). The bite is a
#      deliberate red, evidenced in the log — never "retried away".
#
# FAILURE = verbatim k6 summary lines printed + exit 1.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
API_BASE="${SMOKE_API_BASE:-http://localhost:8080}"
PSP_BASE="${SMOKE_PSP_BASE:-http://localhost:8090}"
K6_IMAGE="${DARGENT_K6_IMAGE:-grafana/k6:latest}"

# E15 spec §4: "tests tune limits explicitly". The webhook limiter is its own concern with its
# own ITs (WebhookRateLimitIT) — the gate measures the synchronous money path, so the run gives
# the webhook intake generous headroom (same posture as the E15 happy baseline) to keep the
# confirm-leg measuring confirm, not the abuse limiter.
export DARGENT_WEBHOOK_RATE_LIMIT_CAPACITY="${DARGENT_WEBHOOK_RATE_LIMIT_CAPACITY:-10000}"
export DARGENT_WEBHOOK_RATE_LIMIT_REFILL_PER_SECOND="${DARGENT_WEBHOOK_RATE_LIMIT_REFILL_PER_SECOND:-100}"

note() { echo "K6-GATE $(date +%T): $*"; }
fail() { echo "K6-GATE FAIL: $*" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# ------------------------------------------------------------------ G1: stack up
for img in dargent-api:compose dargent-psp-simulator:compose; do
    docker image inspect "$img" >/dev/null 2>&1 || fail "image not found: $img (build it first)"
done
compose up -d
"$SCRIPT_DIR/deploy.sh" --wait-ready api-blue  >/dev/null
"$SCRIPT_DIR/deploy.sh" --wait-ready api-green >/dev/null
note "G1 stack up — both colors ready"

# ------------------------------------------------------------------ G2: job-scoped API key
GATE_KEY=$(python3 -c 'import secrets,string; a=string.digits+string.ascii_uppercase+string.ascii_lowercase; print("psp_test_"+"".join(secrets.choice(a) for _ in range(43)))')
[[ "${#GATE_KEY}" -eq 52 ]] || fail "G2: generated key length ${#GATE_KEY} != 52"
KEY_HASH=$(printf '%s' "$GATE_KEY" | sha256sum | cut -d' ' -f1)
compose exec -T postgres psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q \
    -c "delete from payments.api_keys where key_prefix = 'psp_test_'" \
    -c "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at) values (gen_random_uuid(), 'a0000000-0000-4000-8000-000000000001', 'ci-k6-gate', 'psp_test_', '${KEY_HASH}', now())"
note "G2 key inserted (prefix psp_test_)"

run_k6() { # $1 = extra env (-e KEY=VAL); assumes GATE_KEY set
    docker run --rm --network host \
        -e DARGENT_K6_API_BASE="$API_BASE" -e DARGENT_K6_PSP_BASE="$PSP_BASE" \
        -e DARGENT_K6_API_KEY="$GATE_KEY" "$@" \
        -v "$REPO_DIR/scripts/load:/scripts:ro" "$K6_IMAGE" run /scripts/k6-gate.js
}

# ------------------------------------------------------------------ G3: gate GREEN
note "G3 gate run — create p95<75ms, http_req_failed==0"
if ! run_k6 | tee /tmp/k6-gate-green.log; then
    fail "G3: k6 gate RED — thresholds above; see summary"
fi
# evidence line: the create-leg p95 specifically (the global first p(95)= in the log is the catch-all)
CREATE_P95=$(sed -n '/name:create/p' /tmp/k6-gate-green.log | grep -o 'p(95)=[0-9.]*ms' | head -1 || true)
note "G3 gate GREEN ($(grep -c '✓' /tmp/k6-gate-green.log || true) checks) create-p95=${CREATE_P95:-unparsed}"

# ------------------------------------------------------------------ G4: bite-proof (must exit RED)
note "G4 BITE-PROOF — impossible threshold (p95<1ms); the gate MUST fail here"
BITE_EXIT=0
run_k6 -e DARGENT_K6_GATE_BITE=1 > /tmp/k6-gate-bite.log 2>&1 || BITE_EXIT=$?
if [[ "$BITE_EXIT" -eq 0 ]]; then
    cat /tmp/k6-gate-bite.log
    fail "G4: bite-proof did NOT trip — the gate cannot bite (threshold wiring is broken)"
fi
grep -q "p(95)<1" /tmp/k6-gate-bite.log || {
    cat /tmp/k6-gate-bite.log
    fail "G4: bite log lacks the impossible threshold line — wrong script/config ran"
}
note "G4 bite-proof RED as designed (exit ${BITE_EXIT}) — the gate bites"

note "K6-GATE PASS (G1-G4)"
#!/usr/bin/env bash
#
# Dargent daily ledger proof (E12 S4 rider N8): boots the FULL event spine (relay + ledger
# consumer + notifications), pushes one real payment through money path (smoke), waits for the
# ledger to journal it, then runs the PROOF:
#   P1 stack up (postgres + localstack + api + psp-simulator, spine ON) and readiness on :9090
#   P2 job-scoped API key (same contract as ci-runtime-smoke.sh — Q2)
#   P3 money path: scripts/smoke.sh (create → replay → pay → webhook CONFIRMED)
#   P4 wait for the ledger to journal the confirmation (outbox → SNS → SQS → ingestion)
#   P5 PROOF via the API: GET /v1/ledger/proof → ok:true (Σ DR = Σ CR + per-account projection)
#      — exit status IS the source of truth (N8)
#   P6 DB-level corroboration: journal posted for the txid, Σ DR == Σ CR, projection == lines
# FAILURE = logs printed + exit 1 (workflow uploads them as an artifact).
#
# This script is the `proof-daily` CI job body (cron daily + workflow_dispatch for the first run).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
API_BASE="${SMOKE_API_BASE:-http://localhost:8080}"
PSP_BASE="${SMOKE_PSP_BASE:-http://localhost:8090}"
# P4 budget: relay poll (1s) + SNS/SQS FIFO propagation + consumer poll (1s) + CI margin.
LEDGER_INGEST_DEADLINE_S="${LEDGER_INGEST_DEADLINE_S:-60}"

note() { echo "PROOF-DAILY $(date +%T): $*"; }
fail() { echo "PROOF-DAILY FAIL: $*" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# ------------------------------------------------------------------ P0: prerequisites
for img in dargent-api:compose dargent-psp-simulator:compose; do
    docker image inspect "$img" >/dev/null 2>&1 || fail "image not found: $img (build it first)"
done
[[ -f "$REPO_DIR/deploy/runtime/nginx.conf" ]] || "$SCRIPT_DIR/deploy.sh" --init

# The spine must be ON for this job (relay + ledger consumer + notifications consumer); the
# reconciler is not needed for the proof (webhook confirms the payment) but harmless.
export DARGENT_RELAY_ENABLED=true
export DARGENT_RELAY_POLL_MS=1000
export DARGENT_LEDGER_CONSUMER_ENABLED=true
export DARGENT_LEDGER_POLL_MS=1000
export DARGENT_RECONCILER_ENABLED=true
export DARGENT_RECONCILER_SCAN_MS=2000

# E13 R3: /v1/ledger/proof is admin-gated — the job-scoped smoke key (P1) doubles as the
# designated admin key for this run. Generated BEFORE compose up so both see the same value.
SMOKE_KEY=$(python3 -c 'import secrets,string; a=string.digits+string.ascii_uppercase+string.ascii_lowercase; print("psp_test_"+"".join(secrets.choice(a) for _ in range(43)))')
[[ "${#SMOKE_KEY}" -eq 52 ]] || fail "P0: generated key length ${#SMOKE_KEY} != 52"
export DARGENT_LEDGER_ADMIN_KEY="$SMOKE_KEY"

note "P0 stack up (event spine ON: relay + ledger consumer)"
compose up -d
"$SCRIPT_DIR/deploy.sh" --wait-ready api-blue >/dev/null

# ------------------------------------------------------------------ P1: job-scoped API key (Q2 contract)
KEY_HASH=$(printf '%s' "$SMOKE_KEY" | sha256sum | cut -d' ' -f1)
compose exec -T postgres psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q \
    -c "delete from payments.api_keys where key_prefix = 'psp_test_'" \
    -c "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at) values (gen_random_uuid(), 'a0000000-0000-4000-8000-000000000001', 'ci-proof-daily', 'psp_test_', '${KEY_HASH}', now())"
note "P1 key inserted (prefix psp_test_)"

# ------------------------------------------------------------------ P2: money path
SMOKE_OUT=$( "$SCRIPT_DIR/smoke.sh" "$API_BASE" "$SMOKE_KEY" "$PSP_BASE" 2>&1 ) \
    || { printf '%s\n' "$SMOKE_OUT" >&2; fail "P2: smoke money path failed"; }
printf '%s\n' "$SMOKE_OUT" >&2
TXID=$(grep -o 'txid=[A-Z0-9]*' <<<"$SMOKE_OUT" | head -1 | cut -d= -f2)
[[ -n "$TXID" ]] || fail "P2: could not extract txid from smoke output"
note "P2 money path ok — txid=$TXID CONFIRMED via webhook"

# ------------------------------------------------------------------ P3: wait for ledger ingestion
# The confirmation event travels outbox → SNS → SQS ledger queue → ingestion (POSTED + postings).
DEADLINE=$(( $(date +%s) + LEDGER_INGEST_DEADLINE_S ))
INGESTED=""
while (( $(date +%s) < DEADLINE )); do
    INGESTED=$(compose exec -T postgres psql -U dargent -d dargent -tAc \
        "select count(*) from ledger.journal_entries je where je.txid = '$TXID'" 2>/dev/null | tr -d '[:space:]' || true)
    [[ "$INGESTED" == "1" ]] && break
    sleep 2
done
[[ "$INGESTED" == "1" ]] || fail "P3: ledger did not journal txid $TXID within ${LEDGER_INGEST_DEADLINE_S}s (spine down? relay? consumer?)"
note "P3 ledger ingested txid=$TXID (journal entry POSTED)"

# ------------------------------------------------------------------ P4: PROOF via the API
PROOF=$(command curl -sS -H "Authorization: Bearer ${SMOKE_KEY}" "$API_BASE/v1/ledger/proof" 2>/dev/null || true)
[[ -n "$PROOF" ]] || fail "P4: /v1/ledger/proof unreachable"
grep -q '"ok":true' <<<"$PROOF" \
    || fail "P4: proof FAILED — $PROOF"
note "P4 proof ok — $(grep -o '"accountsChecked":[0-9]*,"entriesChecked":[0-9]*,"postingsChecked":[0-9]*' <<<"$PROOF")"

# ------------------------------------------------------------------ P5: DB corroboration (Σ DR = Σ CR + projection == lines)
SUMS=$(compose exec -T postgres psql -U dargent -d dargent -tAc \
    "select (select coalesce(sum(amount_cents),0) from ledger.postings where direction='DEBIT'), (select coalesce(sum(amount_cents),0) from ledger.postings where direction='CREDIT')")
DR=$(awk -F'|' '{print $1}' <<<"$SUMS" | tr -d '[:space:]')
CR=$(awk -F'|' '{print $2}' <<<"$SUMS" | tr -d '[:space:]')
[[ "$DR" == "$CR" ]] || fail "P5: Σ DR ($DR) != Σ CR ($CR) at the DB level"
PROJECTION=$(compose exec -T postgres psql -U dargent -d dargent -tAc \
    "select count(*) from ledger.balances b where b.balance_cents != (select coalesce(sum(case when p.direction='CREDIT' then p.amount_cents else -p.amount_cents end),0) from ledger.postings p where p.account=b.account)")
PROJECTION=$(tr -d '[:space:]' <<<"$PROJECTION")
[[ "$PROJECTION" == "0" ]] || fail "P5: balances projection diverges from journal lines for $PROJECTION account(s)"
note "P5 DB corroboration ok — Σ DR = Σ CR = $DR cents, projection == lines"

note "PROOF-DAILY PASS (P0-P5) — txid $TXID journaled and proven"

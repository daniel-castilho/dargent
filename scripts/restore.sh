#!/usr/bin/env bash
#
# Dargent restore (E14 S3 — runbook §6, verbatim behavior). Restore a pg_dump -Fc archive into a
# FRESH cluster, run Flyway (no-op expected — any pending migration = dump/code mismatch = abort),
# boot the app, verify per-table counts against the dump manifest, run the balance proof
# (Σ DR = Σ CR + projection == lines). Exit non-zero on ANY mismatch.
#
# Traffic NEVER returns over an unverified restore: this script does not touch NGINX weights and
# does not start traffic (operator act). It prints the go/no-go line as its last output.
#
# Usage: scripts/restore.sh <dump> [--fresh] [--compose-file <path>]
#   <dump>   path to dargent-YYYYMMDD-HHMMSS.dump (its .manifest.json sibling is REQUIRED)
#   --fresh  destroy the current cluster (docker compose down -v) before restoring — the drill path
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

DUMP="${1:?usage: restore.sh <dump> [--fresh] (manifest sibling required)}"
FRESH=false
[[ "${2:-}" == "--fresh" ]] && FRESH=true

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
DB_USER="${DARGENT_DB_USER:-dargent}"
DB_NAME="${DARGENT_DB_NAME:-dargent}"
API="${DARGENT_RESTORE_API:-api-blue}"

note() { echo "RESTORE $(date +%T): $*"; }
die()  { echo "RESTORE $(date +%T) ABORT: $*" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" ${DARGENT_COMPOSE_OVERRIDE:+-f "$DARGENT_COMPOSE_OVERRIDE"} "$@"; }

MANIFEST="${DUMP%.dump}.manifest.json"
[[ -f "$DUMP" ]] || die "dump not found: $DUMP"
[[ -s "$DUMP" ]] || die "dump is empty: $DUMP"
[[ -f "$MANIFEST" ]] || die "manifest not found next to the dump: $MANIFEST (backup.sh writes both)"

RTO_START=$(date +%s)

# ------------------------------------------------------------------ 1. fresh cluster
if $FRESH; then
    note "destroying current cluster (down -v) for a fresh restore"
    compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
compose up -d postgres >/dev/null || die "postgres did not start"
for _ in $(seq 1 30); do
    compose exec -T postgres pg_isready -U "$DB_USER" >/dev/null 2>&1 && break
    sleep 2
done
compose exec -T postgres pg_isready -U "$DB_USER" >/dev/null 2>&1 || die "postgres not ready after 60s"

# ------------------------------------------------------------------ 2. pg_restore
note "pg_restore -Fc into fresh cluster (schema $DB_NAME)"
compose cp "$DUMP" postgres:/tmp/restore.dump || die "could not copy dump into the container"
compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --no-privileges /tmp/restore.dump \
    || die "pg_restore failed (dump corrupt or cluster not fresh)"
compose exec -T postgres rm -f /tmp/restore.dump

# ------------------------------------------------------------------ 3. Flyway no-op check (boot runs it)
# Boot the API: Flyway runs at startup. NO-OP EXPECTED — the history table after restore (H0)
# must equal the table after boot (H1). H1 > H0 means migrations ran on top of the dump: the dump
# predates the code = mismatch = abort (backlog S3 §2). Boot failure for ANY reason = abort.
H0=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select count(*) from public.flyway_schema_history" 2>/dev/null | tr -d '[:space:]' || echo "0")
note "booting $API (Flyway runs at startup — no-op expected; history rows at restore: $H0)"
compose up -d "$API" >/dev/null || die "api container did not start"
READY=""
for _ in $(seq 1 45); do
    BODY=$(compose exec -T "$API" wget -qO- http://127.0.0.1:9090/actuator/health 2>/dev/null || true)
    grep -q '"status":"UP"' <<<"$BODY" && { READY=UP; break; }
    sleep 2
done
[[ "$READY" == "UP" ]] || die "$API did not become healthy after restore (boot/migration failure)"

H1=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select count(*) from public.flyway_schema_history" 2>/dev/null | tr -d '[:space:]' || echo "0")
[[ "$H0" == "$H1" ]] \
    || die "Flyway was NOT a no-op: history rows went $H0 → $H1 after boot — the dump predates the code (dump/code mismatch)"
PENDING=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select count(*) from public.flyway_schema_history where success = false" 2>/dev/null | tr -d '[:space:]' || echo "n/a")
[[ "$PENDING" == "0" || "$PENDING" == "n/a" ]] \
    || die "Flyway reports $PENDING failed migrations after restore — dump/code mismatch"

# ------------------------------------------------------------------ 4. per-table counts vs manifest
note "verifying per-table counts against the dump manifest"
FAILURES=0
while IFS='=' read -r TABLE EXPECTED; do
    [[ -z "$TABLE" ]] && continue
    ACTUAL=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "select count(*) from $TABLE" | tr -d '[:space:]')
    if [[ "$ACTUAL" != "$EXPECTED" ]]; then
        echo "MISMATCH: $TABLE expected=$EXPECTED actual=$ACTUAL" >&2
        FAILURES=$((FAILURES+1))
    fi
done < <(python3 -c "
import json,sys
m=json.load(open('$MANIFEST'))
for t,c in m['tables'].items(): print(f'{t}={c}')
")
[[ "$FAILURES" -eq 0 ]] || die "$FAILURES table count mismatch(es) against the manifest — restore UNVERIFIED"

# ------------------------------------------------------------------ 5. balance proof (ΣDR=ΣCR + projection==lines)
SUMS=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select (select coalesce(sum(amount_cents),0) from ledger.postings where direction='DEBIT'), (select coalesce(sum(amount_cents),0) from ledger.postings where direction='CREDIT')")
DR=$(awk -F'|' '{print $1}' <<<"$SUMS" | tr -d '[:space:]')
CR=$(awk -F'|' '{print $2}' <<<"$SUMS" | tr -d '[:space:]')
[[ "$DR" == "$CR" ]] || die "balance proof FAILED: Σ DR ($DR) != Σ CR ($CR) after restore"
PROJECTION=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select count(*) from ledger.balances b where b.balance_cents != (select coalesce(sum(case when p.direction='CREDIT' then p.amount_cents else -p.amount_cents end),0) from ledger.postings p where p.account=b.account)")
PROJECTION=$(tr -d '[:space:]' <<<"$PROJECTION")
[[ "$PROJECTION" == "0" ]] || die "balance proof FAILED: projection diverges from journal lines for $PROJECTION account(s)"

# The manifest snapshot must also agree (a dump whose ledger was already broken restores broken).
M_DR=$(python3 -c "import json; m=json.load(open('$MANIFEST')); print(m['ledgerSums']['debitCents'])")
M_CR=$(python3 -c "import json; m=json.load(open('$MANIFEST')); print(m['ledgerSums']['creditCents'])")
[[ "$M_DR" == "$DR" && "$M_CR" == "$CR" ]] || die "restored ΣDR/ΣCR ($DR/$CR) differ from manifest snapshot ($M_DR/$M_CR)"

RTO_SECONDS=$(( $(date +%s) - RTO_START ))
note "balance proof ok — Σ DR = Σ CR = $DR cents, projection == lines"

echo "GO/NO-GO: GO — restore verified (counts match manifest, ΣDR=ΣCR=$DR, projection==lines) in ${RTO_SECONDS}s — traffic release is the OPERATOR act (this script never starts traffic)"
note "RESTORE OK (RTO ${RTO_SECONDS}s)"

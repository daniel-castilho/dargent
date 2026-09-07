#!/usr/bin/env bash
#
# Dargent backup (E14 S3 — runbook §6): pg_dump -Fc against the running stack + a manifest
# (per-table row counts + ΣDR/ΣCR snapshot + dump size + pg version + timestamp) written
# alongside the dump. Rotation-aware naming: dargent-YYYYMMDD-HHMMSS.dump (+ .manifest.json).
#
# The manifest is the restore contract: scripts/restore.sh re-counts the same tables after
# restore and exits non-zero on ANY mismatch — a backup without a verified restore is a hope.
#
# Usage: scripts/backup.sh [--out-dir <dir>] [--compose-file <path>]
#   --out-dir    default: backups/ (gitignored)
# Env: DARGENT_DB_USER / DARGENT_DB_NAME (defaults dargent/dargent, matching compose).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

OUT_DIR="${DARGENT_BACKUP_DIR:-$REPO_DIR/backups}"
COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
DB_USER="${DARGENT_DB_USER:-dargent}"
DB_NAME="${DARGENT_DB_NAME:-dargent}"

note() { echo "BACKUP $(date +%T): $*"; }
die()  { echo "BACKUP $(date +%T) ABORT: $*" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" ${DARGENT_COMPOSE_OVERRIDE:+-f "$DARGENT_COMPOSE_OVERRIDE"} "$@"; }

# Tables whose row counts make up the restore manifest (every business table, all three
# schemas — schema-per-module, AGENTS §2.4).
MANIFEST_TABLES=(
    payments.payments payments.refunds payments.api_keys payments.idempotency_keys
    payments.webhook_events payments.outbox payments.audit_log
    ledger.journal_entries ledger.postings ledger.balances ledger.events
    ledger.settlements ledger.audit_log
    notifications.notification
)

# ------------------------------------------------------------------ preconditions (fail-closed)
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR" || die "cannot create output dir: $OUT_DIR"
compose ps --status running postgres 2>/dev/null | grep -q postgres \
    || die "postgres container is not running (compose file: $COMPOSE_FILE)"

# ------------------------------------------------------------------ pg_dump -Fc
DUMP="$OUT_DIR/dargent-$STAMP.dump"
note "pg_dump -Fc → $DUMP"
compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -f "/tmp/dargent-$STAMP.dump" \
    || die "pg_dump failed inside the container"
compose cp postgres:/tmp/dargent-$STAMP.dump "$DUMP" \
    || die "could not copy the dump out of the container"
compose exec -T postgres rm -f "/tmp/dargent-$STAMP.dump"
[[ -s "$DUMP" ]] || die "dump is empty: $DUMP"

# ------------------------------------------------------------------ manifest
PG_VERSION=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "show server_version" | tr -d '[:space:]')
DUMP_SIZE=$(stat -c %s "$DUMP")
TS_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

MANIFEST="$OUT_DIR/dargent-$STAMP.manifest.json"
{
    printf '{\n'
    printf '  "tool": "scripts/backup.sh (E14 S3, runbook §6)",\n'
    printf '  "timestamp": "%s",\n' "$TS_ISO"
    printf '  "postgresVersion": "%s",\n' "$PG_VERSION"
    printf '  "dumpFile": "%s",\n' "$(basename "$DUMP")"
    printf '  "dumpSizeBytes": %s,\n' "$DUMP_SIZE"
    printf '  "tables": {\n'
    first=true
    for t in "${MANIFEST_TABLES[@]}"; do
        COUNT=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "select count(*) from $t" | tr -d '[:space:]')
        if $first; then first=false; else printf ',\n'; fi
        printf '    "%s": %s' "$t" "$COUNT"
    done
    printf '\n  },\n'
    SUMS=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
        "select (select coalesce(sum(amount_cents),0) from ledger.postings where direction='DEBIT'), (select coalesce(sum(amount_cents),0) from ledger.postings where direction='CREDIT')")
    DR=$(awk -F'|' '{print $1}' <<<"$SUMS" | tr -d '[:space:]')
    CR=$(awk -F'|' '{print $2}' <<<"$SUMS" | tr -d '[:space:]')
    printf '  "ledgerSums": { "debitCents": %s, "creditCents": %s, "balanced": %s }\n' "$DR" "$CR" "$([[ "$DR" == "$CR" ]] && echo true || echo false)"
    printf '}\n'
} > "$MANIFEST"

note "manifest written: $MANIFEST (ΣDR=$DR ΣCR=$CR)"
note "BACKUP OK — $(basename "$DUMP") ($(numfmt --to=iec "$DUMP_SIZE" 2>/dev/null || echo "${DUMP_SIZE}B")) + manifest"

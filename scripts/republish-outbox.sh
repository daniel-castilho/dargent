#!/usr/bin/env bash
#
# Dargent outbox republish (E14 S3 — runbook §6, Q2 Proposal A adjudicated 2026-09-07).
# Wrapper over POST /v1/outbox/republish (OutboxAdminController, E9 §4):
#   body {"from","to","types"} — to exclusive, window ≤ 30d, cap 500 rows/call;
#   response {"matched":N,"republished":M}.
#
# Conditions (owner-adjudicated):
#   (i)   print matched vs republished; exit NON-ZERO when matched > republished
#         (the 500/call cap must never silently under-republish);
#   (ii)  fail-closed preconditions with actionable messages: admin key unset;
#         key without an active row in payments.api_keys (precedent ci-proof-daily:58-61);
#   (iii) relay OFF → WARN and continue (republished rows persist in the outbox until the
#         relay runs — precedent ci-proof-daily:40 for the ON case). A 404 on the call means
#         the controller is not registered (relay-gated bean) — reported with the reason.
#
# Usage: scripts/republish-outbox.sh --from <ISO-8601> [--to <ISO-8601>] [--types payment.confirmed,...]
#   --from  REQUIRED ISO-8601 instant, e.g. 2026-09-07T00:00:00Z (inclusive)
#   --to    optional ISO-8601 instant, EXCLUSIVE upper bound; default: now
#   --types optional comma-separated event types filter (e.g. payment.confirmed,refund.created)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

API_BASE="${DARGENT_API_BASE:-http://localhost:8080}"
COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
DB_USER="${DARGENT_DB_USER:-dargent}"
DB_NAME="${DARGENT_DB_NAME:-dargent}"

note() { echo "REPUBLISH $(date +%T): $*"; }
die()  { echo "REPUBLISH $(date +%T) ABORT: $*" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

FROM=""
TO=""
TYPES=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --from) FROM="${2:?--from requires an ISO-8601 value}"; shift 2 ;;
        --to)   TO="${2:?--to requires an ISO-8601 value}"; shift 2 ;;
        --types) TYPES="${2:?--types requires a comma-separated list}"; shift 2 ;;
        *) die "unknown argument: $1 (usage: --from <ts> [--to <ts>] [--types a,b])" ;;
    esac
done

[[ -n "$FROM" ]] || die "usage: republish-outbox.sh --from <ISO-8601> [--to <ISO-8601>] [--types a,b] (from is REQUIRED)"
[[ -n "$TO" ]] || TO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FROM_EPOCH=$(date -u -d "$FROM" +%s 2>/dev/null) || die "--from is not a valid ISO-8601 instant: $FROM"
TO_EPOCH=$(date -u -d "$TO" +%s 2>/dev/null) || die "--to is not a valid ISO-8601 instant: $TO"
[[ "$FROM_EPOCH" -le "$TO_EPOCH" ]] || die "from ($FROM) must be before or equal to to ($TO)"
(( TO_EPOCH - FROM_EPOCH <= 30*24*3600 )) || die "window exceeds 30 days (endpoint contract)"
# Types tokens feed a SQL IN-list — reject anything outside event-type shape (fail-closed).
[[ -z "$TYPES" || "$TYPES" =~ ^[a-z0-9._]+(,[a-z0-9._]+)*$ ]] \
    || die "--types must be comma-separated event types (e.g. payment.confirmed,refund.created): $TYPES"

# ------------------------------------------------------------------ precondition (ii): admin key fail-closed
ADMIN_KEY="${DARGENT_OUTBOX_ADMIN_KEY:-}"
if [[ -z "$ADMIN_KEY" ]]; then
    cat >&2 <<EOF
REPUBLISH ABORT: DARGENT_OUTBOX_ADMIN_KEY is unset.
  The republish route is 404-hidden without it (E9 §4.1). Export the admin key:
    export DARGENT_OUTBOX_ADMIN_KEY=<admin-key-value>
  The key must be a REAL API key row (payments.api_keys) — same contract as the ledger
  admin key (ci-proof-daily:58-61 inserts the job-scoped smoke key the same way).
EOF
    exit 1
fi
KEY_HASH=$(printf '%s' "$ADMIN_KEY" | sha256sum | cut -d' ' -f1)
KEY_PREFIX="${ADMIN_KEY:0:9}"
ROW=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select count(*) from payments.api_keys where key_hash = '$KEY_HASH'" 2>/dev/null | tr -d '[:space:]' || echo "0")
if [[ "$ROW" != "1" ]]; then
    cat >&2 <<EOF
REPUBLISH ABORT: the admin key (prefix '$KEY_PREFIX…') has no active row in payments.api_keys.
  Insert it first (job-scoped precedent, ci-proof-daily:58-61):
    KEY_HASH=$(printf '%s' "$ADMIN_KEY" | sha256sum | cut -d' ' -f1)
    docker compose -f $COMPOSE_FILE exec -T postgres psql -U $DB_USER -d $DB_NAME \\
      -c "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at) \\
          values (gen_random_uuid(), '<merchant-uuid>', 'outbox-admin', '$KEY_PREFIX', '$KEY_HASH', now())"
EOF
    exit 1
fi

# ------------------------------------------------------------------ precondition (iii): relay OFF → WARN, continue
RELAY_ON=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select 1" >/dev/null 2>&1 && compose exec -T api-blue printenv DARGENT_RELAY_ENABLED 2>/dev/null | tr -d '[:space:]' || echo "")
if [[ "$RELAY_ON" != "true" ]]; then
    echo "REPUBLISH WARN: relay is OFF (DARGENT_RELAY_ENABLED != true). Proceeding — republished rows" >&2
    echo "  persist as PENDING in the outbox and publish when the relay runs (ci-proof-daily:40 precedent)." >&2
fi

# ------------------------------------------------------------------ the call
BODY="{\"from\":\"$FROM\",\"to\":\"$TO\""
[[ -n "$TYPES" ]] && BODY="$BODY,\"types\":[$(sed 's/[^,]*/"&"/g' <<<"$TYPES" | tr -d '\n' | sed 's/,/, /g')]"
BODY="$BODY}"

note "POST $API_BASE/v1/outbox/republish $BODY"
HTTP=$(command curl -sS -o /tmp/republish.body -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $ADMIN_KEY" -H "Content-Type: application/json" \
    -d "$BODY" "$API_BASE/v1/outbox/republish" 2>/dev/null || true)

case "$HTTP" in
    200) ;;
    404) die "404 — the outbox admin surface is not registered: relay is OFF (DARGENT_RELAY_ENABLED != true → @ConditionalOnProperty hides the controller). Start the stack with DARGENT_RELAY_ENABLED=true, then re-run. Body: $(cat /tmp/republish.body 2>/dev/null)" ;;
    *)   die "unexpected HTTP $HTTP from the republish endpoint (body: $(cat /tmp/republish.body 2>/dev/null))" ;;
esac

MATCHED=$(grep -o '"matched":[0-9]*' /tmp/republish.body | cut -d: -f2)
REPUBLISHED=$(grep -o '"republished":[0-9]*' /tmp/republish.body | cut -d: -f2)
[[ -n "$MATCHED" && -n "$REPUBLISHED" ]] || die "could not parse matched/republished from: $(cat /tmp/republish.body)"

# ------------------------------------------------------------------ condition (i): never silently under-republish
# The endpoint caps at 500 rows/call (SELECT ... order by published_at limit 500) and the
# ORIGINALS STAY SENT (republishSent inserts PENDING copies; it does not consume the window).
# Re-running the same >500 window would re-match the same first 500 rows forever — the only
# sound contract: a call is clean when it covered the WHOLE window (window_rows ≤ 500 and
# republished == window_rows). The script counts the full window directly (it already holds
# DB access for the key precondition) and fails on any shortfall, naming the fix.
TYPES_SQL=""
if [[ -n "$TYPES" ]]; then
    TYPES_SQL=" and type in ('${TYPES//,/','}')"
fi
WINDOW_COUNT=$(compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select count(*) from payments.outbox where status='SENT' and published_at >= '$FROM' and published_at < '$TO'$TYPES_SQL" 2>/dev/null | tr -d '[:space:]' || echo "n/a")

note "matched=$MATCHED republished=$REPUBLISHED window_rows=${WINDOW_COUNT:-n/a} (cap 500/call)"

if (( MATCHED > REPUBLISHED )); then
    cat >&2 <<EOF
REPUBLISH FAIL: matched ($MATCHED) > republished ($REPUBLISHED).
  Rows were lost to races/constraints — re-run the same window before proceeding.
  The tool never silently under-republishes (owner condition i).
EOF
    exit 1
fi
if [[ "$WINDOW_COUNT" == "n/a" ]]; then
    echo "REPUBLISH WARN: could not count the window rows (DB unreachable) — matched=$MATCHED republished=$REPUBLISHED not corroborated" >&2
elif (( WINDOW_COUNT > 500 )); then
    cat >&2 <<EOF
REPUBLISH FAIL: the window holds $WINDOW_COUNT SENT row(s) — over the 500/call cap. This call
  republished $REPUBLISHED; the rest are UNREACHABLE by re-running (originals stay SENT, the
  endpoint re-matches the same first 500 by published_at). Split the window, e.g.:
    scripts/republish-outbox.sh --from $FROM --to <midpoint> --types ...
    scripts/republish-outbox.sh --from <midpoint> --to $TO --types ...
  The tool never silently under-republishes (owner condition i).
EOF
    exit 1
elif (( WINDOW_COUNT > REPUBLISHED )); then
    cat >&2 <<EOF
REPUBLISH FAIL: the window holds $WINDOW_COUNT SENT row(s) but this call republished $REPUBLISHED.
  Re-run the same window (rows may have raced); if it persists, narrow the window and retry.
  The tool never silently under-republishes (owner condition i).
EOF
    exit 1
fi

note "REPUBLISH OK — matched=$MATCHED republished=$REPUBLISHED window_rows=$WINDOW_COUNT (fully covered)"

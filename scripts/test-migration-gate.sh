#!/usr/bin/env bash
#
# Migration-gate tests (TD-33, D16 refined policy). Builds scratch git trees so the FULL CLI path is
# exercised: git diff range + tree_index + CHECK set comparison + ABORT/ALLOW/parse-unknown verdicts.
# Four cases per owner decision: (1) widening passes, (2) destructive aborts, (3) CHECK narrowing
# aborts, (4) parse-unknown aborts. Exit non-zero on any expectation mismatch (CI gate).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="$SCRIPT_DIR/migration_gate.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); echo "PASS $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "FAIL $1"; }

# scene <name> <old_files_arr> <new_files_arr> <expected: pass|fail>
# Uses assoc arrays old_sql / new_sql keyed by file path (reset per scene by the caller).
scene() {
    local name="$1" expected="$4"
    local -n oldarr=$2 newarr=$3
    local repo="$TMP/$name"
    git init -q -b main "$repo"
    git -C "$repo" config user.email gate@test
    git -C "$repo" config user.name gate
    for f in "${oldarr[@]}"; do
        mkdir -p "$(dirname "$repo/$f")"
        printf '%s\n' "${old_sql[$f]:-}" > "$repo/$f"
    done
    git -C "$repo" add -A >/dev/null
    git -C "$repo" commit -qm old >/dev/null
    for f in "${newarr[@]}"; do
        mkdir -p "$(dirname "$repo/$f")"
        printf '%s\n' "${new_sql[$f]:-}" > "$repo/$f"
    done
    git -C "$repo" add -A >/dev/null
    git -C "$repo" commit -qm new >/dev/null
    local out rc
    out=$(python3 "$PY" --repo "$repo" check HEAD~1 HEAD 2>&1) && rc=0 || rc=$?
    if [[ "$expected" == "pass" && $rc -eq 0 ]]; then ok "$name ($(echo "$out" | tail -1))"
    elif [[ "$expected" == "fail" && $rc -ne 0 ]]; then ok "$name (aborted: $(echo "$out" | grep -m1 FAIL))"
    else bad "$name — expected '$expected', rc=$rc, output: $(echo "$out" | tr '\n' ' ')"; fi
}

P="m/db/migration/ledger"
Q="m/db/migration/payments"

# ---- 1. CHECK widening (V207 shape) -> PASS
declare -A old_sql new_sql
E="CREATE TABLE ledger.events (
    id bigint primary key,
    status varchar(16) NOT NULL CHECK (status IN ('POSTED','IGNORED','REJECTED'))
);"
old_sql["$P/V202__journal.sql"]="$E"
new_sql["$P/V202__journal.sql"]="$E"
new_sql["$P/V207__events_status_includes_received.sql"]="
ALTER TABLE ledger.events DROP CONSTRAINT events_status_check;
ALTER TABLE ledger.events ADD CONSTRAINT events_status_check
    CHECK (status IN ('RECEIVED','POSTED','IGNORED','REJECTED'));
"
old_files=("$P/V202__journal.sql")
new_files=("$P/V202__journal.sql" "$P/V207__events_status_includes_received.sql")
scene "1-widening" old_files new_files pass

# ---- 2. destructive DROP COLUMN -> ABORT
declare -A old_sql new_sql
old_sql["$Q/V101__t.sql"]="CREATE TABLE s.t (id bigint);"
new_sql["$Q/V101__t.sql"]="CREATE TABLE s.t (id bigint);"
new_sql["$Q/V110__drop.sql"]="ALTER TABLE s.t DROP COLUMN id;"
old_files=("$Q/V101__t.sql")
new_files=("$Q/V101__t.sql" "$Q/V110__drop.sql")
scene "2-destructive" old_files new_files fail

# ---- 3. CHECK narrowing -> ABORT
declare -A old_sql new_sql
E="CREATE TABLE s.events (id bigint, status varchar(16) NOT NULL CHECK (status IN ('POSTED','IGNORED','REJECTED')));"
old_sql["$P/V202__journal.sql"]="$E"
new_sql["$P/V202__journal.sql"]="$E"
new_sql["$P/V210__narrow.sql"]="
ALTER TABLE s.events DROP CONSTRAINT events_status_check;
ALTER TABLE s.events ADD CONSTRAINT events_status_check
    CHECK (status IN ('POSTED','IGNORED'));
"
old_files=("$P/V202__journal.sql")
new_files=("$P/V202__journal.sql" "$P/V210__narrow.sql")
scene "3-check-narrowing" old_files new_files fail

# ---- 4. parse-unknown statement -> ABORT (fail-closed)
declare -A old_sql new_sql
old_sql["$Q/V101__t.sql"]="CREATE TABLE s.t (id bigint);"
new_sql["$Q/V101__t.sql"]="CREATE TABLE s.t (id bigint);"
new_sql["$Q/V110__unknown.sql"]="DO \$\$ BEGIN RAISE NOTICE 'pik'; END \$\$;"
old_files=("$Q/V101__t.sql")
new_files=("$Q/V101__t.sql" "$Q/V110__unknown.sql")
scene "4-parse-unknown" old_files new_files fail

echo
echo "migration-gate tests: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
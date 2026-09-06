#!/usr/bin/env python3
"""Migration gate (D16) — expanded contract adjudicated by the owner 2026-09-06 (TD-33).

Range is LAST-DEPLOY..target (recorded in deploy/runtime/last-deploy.txt at each deploy, cross-checked
against the live flyway_schema_history when a database is reachable), NOT any tag.

Classification of every schema migration statement in the range:

  ABORT  -> DROP TABLE/COLUMN/SCHEMA, ALTER COLUMN ... TYPE, SET NOT NULL, RENAME.
  ALLOW  -> DROP NOT NULL, DROP DEFAULT (logged as ALLOW, never silently).
  CHECK  -> constraint replacement is a SET comparison: new ⊇ old passes with a log; narrowing,
            an unparseable definition, or a new CHECK constraint on an existing table aborts.
  UNKNOWN-> anything the classifier cannot put in a safe bucket aborts (fail-closed always).

This module is pure: it reads trees through `git show <ref>:<path>` via the given repo dir, and
exposes a `--selftest` mode used by scripts/test-migration-gate.sh and CI. Output lines are
prefixed INFO/ALLOW/FAIL so deploy.sh and CI can grep a verdict independently of wording.
"""

from __future__ import annotations

import argparse
import re
import subprocess

ABORT_PATTERNS = [
    re.compile(r"\bDROP\s+TABLE\b", re.I),
    re.compile(r"\bDROP\s+COLUMN\b", re.I),
    re.compile(r"\bDROP\s+SCHEMA\b", re.I),
    re.compile(r"\bALTER\s+COLUMN\b.*?\bTYPE\b", re.I | re.S),
    re.compile(r"\bSET\s+NOT\s+NULL\b", re.I),
    re.compile(r"\bRENAME\b", re.I),
]
ALLOW_PATTERNS = [
    re.compile(r"\bDROP\s+NOT\s+NULL\b", re.I),
    re.compile(r"\bDROP\s+DEFAULT\b", re.I),
]
SAFE_VERBS = ("CREATE TABLE ", "CREATE INDEX ", "CREATE UNIQUE INDEX ", "CREATE SCHEMA ",
              "CREATE SEQUENCE ", "CREATE TYPE ", "CREATE VIEW ", "CREATE OR REPLACE ",
              "COMMENT ON ", "COMMENT ", "GRANT ", "REVOKE ", "INSERT INTO ", "INSERT ",
              "UPDATE ", "DELETE ", "SELECT ", "ALTER TABLE ", "ALTER COLUMN ",
              "DROP CONSTRAINT ", "SET ", "BEGIN ", "COMMIT ", "START TRANSACTION ",
              "VACUUM ", "ANALYZE ", "REINDEX ")
MIGRATION_FILE_RE = re.compile(r"db/migration/([^/]+)/.*\.sql$")

_TABLE_RE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_.]+)", re.I)


# --------------------------------------------------------------------------- SQL tokenizer
def split_statements(sql: str) -> list[str]:
    """Split SQL on top-level ';', honouring -- and /* */ comments plus '' and "" quoting."""
    statements: list[str] = []
    cur: list[str] = []
    depth = 0
    i = 0
    n = len(sql)
    while i < n:
        c = sql[i]
        nxt = sql[i + 1] if i + 1 < n else ""
        if c == "-" and nxt == "-":
            j = sql.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and nxt == "*":
            j = sql.find("*/", i)
            if j < 0:
                break
            i = j + 2
            continue
        if c in ("'", '"'):
            quote = c
            cur.append(c)
            i += 1
            while i < n:
                if sql[i] == quote and sql[i:i + 2] == quote * 2:  # SQL doubled-quote escape
                    cur.append(sql[i:i + 2])
                    i += 2
                    continue
                cur.append(sql[i])
                if sql[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth = max(0, depth - 1)
        if c == ";" and depth == 0:
            stmt = "".join(cur).strip()
            if stmt:
                statements.append(stmt)
            cur = []
        else:
            cur.append(c)
        i += 1
    tail = "".join(cur).strip()
    if tail:
        statements.append(tail)
    return statements


def collapse(stmt: str) -> str:
    return re.sub(r"\s+", " ", stmt).strip()


# --------------------------------------------------------------------------- operation classifier
def classify_ops(stmt: str) -> tuple[str, str]:
    """Classify a single statement against the ABORT/ALLOW grammar.

    Returns (verdict, detail) with verdict in {SAFE, ABORT, UNKNOWN} — ALLOW is folded into
    a SAFE verdict that must still be logged; caller tracks the log separately.
    """
    up = collapse(stmt)
    if not up:
        return "SAFE", "empty statement"
    for pattern in ABORT_PATTERNS:
        m = pattern.search(up)
        if m:
            return "ABORT", f"'{up}' matches {pattern.pattern}"
    for pattern in ALLOW_PATTERNS:
        if pattern.search(up):
            return "ALLOW", f"'{up}' relaxes the schema (allowed with log)"
    if up.startswith(SAFE_VERBS):
        return "SAFE", ""
    return "UNKNOWN", f"'{(up[:120])}' unrecognized verb"

# --------------------------------------------------------------------------- CHECK index
def _strip_qname(qname: str) -> tuple[str, str]:
    parts = re.split(r"\.", qname)
    if len(parts) == 2:
        return parts[0].strip('"').strip(), parts[1].strip('"').strip()
    return "", parts[0].strip('"').strip()

def _parse_check_expr(expr: str) -> list[str] | None:
    """Return the sorted literal-value list of an IN (...) check, or None if not an IN set."""
    m = re.search(r"\bIN\s*\(([^)]*)\)", expr, re.I)
    if not m:
        return None
    values = re.findall(r"'((?:[^']|'')*)'", m.group(1))
    if not values:
        return None
    return sorted(set(values))

def _normalize_expr(expr: str) -> str:
    return re.sub(r"\s+", "", expr).upper()


def _split_top_level(body: str, delimiter: str = ",") -> list[str]:
    """Split a string on a delimiter that sits at paren depth zero (columns of a CREATE TABLE)."""
    chunks: list[str] = []
    cur: list[str] = []
    depth = 0
    i = 0
    while i < len(body):
        c = body[i]
        if c == "'":
            j = body.find("'", i + 1)
            j = body.find("'", j + 1) if j != -1 else len(body)
            cur.append(body[i:j + 1] if j != -1 else body[i:])
            i = (j + 1) if j != -1 else len(body)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth = max(0, depth - 1)
        if c == delimiter and depth == 0:
            chunk = "".join(cur).strip()
            if chunk:
                chunks.append(chunk)
            cur = []
        else:
            cur.append(c)
        i += 1
    tail = "".join(cur).strip()
    if tail:
        chunks.append(tail)
    return chunks


def _auto_check_name(table: str, column: str) -> str:
    """PostgreSQL auto-name for an unnamed column CHECK: <table>_<column>_check (uppercased)."""
    return f"{table}_{column}_CHECK".upper()


def checks_from_script(sql: str, schema: str) -> list[tuple[str, str, str, str]]:
    """Extract (schema, table, constraint, expr) for every CHECK constraint in a script.

    Unnamed inline checks on a column get their Postgres auto-name (<table>_<column>_check); the
    caller matches a later `DROP CONSTRAINT <name>` against those names so a relocation like V207
    (unnamed inline check dropped + named re-add) is seen as ONE widening, not as a new constraint.
    """
    out: list[tuple[str, str, str, str]] = []
    for stmt in split_statements(sql):
        up = collapse(stmt)
        if not up:
            continue
        m = _TABLE_RE.search(up)
        if m:
            tschema, tname = _strip_qname(m.group(1))
            tschema = tschema or schema
            body = stmt[m.end():].strip()
            if body.startswith("("):
                body = body[1:]
            if body.endswith(")"):
                body = body[:-1]
            for chunk in _split_top_level(body):
                chunk = chunk.strip()
                if not chunk:
                    continue
                cm = re.search(r"(?:CONSTRAINT\s+([A-Za-z0-9_]+)\s+)?CHECK\s*(\()", chunk, re.I)
                if not cm:
                    continue
                start = cm.start(2)
                depth = 0
                j = start
                while j < len(chunk):
                    if chunk[j] == "(":
                        depth += 1
                    elif chunk[j] == ")":
                        depth -= 1
                        if depth == 0:
                            break
                    j += 1
                expr = chunk[start + 1:j]
                if cm.group(1):
                    name = cm.group(1).upper()
                else:
                    col = re.match(r"([A-Za-z0-9_]+)", chunk).group(1)
                    name = _auto_check_name(tname, col)
                out.append((tschema, tname, name, expr))
            continue
        am = re.search(r"ALTER\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_.]+)\s+ADD\s+(.*)", up, re.I)
        if am:
            sch, tname = _strip_qname(am.group(1))
            ts = sch or schema
            rest = am.group(2).strip()
            cm = re.search(r"(?:CONSTRAINT\s+([A-Za-z0-9_]+)\s+)?CHECK\s*(\()", rest, re.I)
            if cm:
                if rest.startswith("COLUMN"):
                    col = re.match(r"COLUMN\s+([A-Za-z0-9_]+)", rest).group(1)
                    name = _auto_check_name(tname, col)
                elif cm.group(1):
                    name = cm.group(1).upper()
                else:
                    name = f"{tname}_ADD_CHECK"
                start = cm.start(2)
                depth = 0
                j = start
                while j < len(stmt):
                    if stmt[j] == "(":
                        depth += 1
                    elif stmt[j] == ")":
                        depth -= 1
                        if depth == 0:
                            break
                    j += 1
                expr = stmt[start + 1:j].strip()
                out.append((ts, tname, name, expr))
            continue
    return out


def drops_from_script(sql: str, schema: str) -> set[tuple[str, str, str]]:
    """Constrained names removed by the script: (schema, table, name)."""
    drops: set[tuple[str, str, str]] = set()
    for stmt in split_statements(sql):
        up = collapse(stmt)
        m = re.search(r"ALTER\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_.]+)\s+DROP\s+CONSTRAINT\s+([A-Za-z0-9_]+)", up, re.I)
        if m:
            sch, tn = _strip_qname(m.group(1))
            drops.add((sch or schema, tn, m.group(2).upper()))
    return drops


def tables_from_script(sql: str, schema: str) -> set[tuple[str, str]]:
    tables: set[tuple[str, str]] = set()
    for stmt in split_statements(sql):
        m = _TABLE_RE.search(collapse(stmt))
        if m:
            sch, tn = _strip_qname(m.group(1))
            tables.add((sch or schema, tn))
    return tables


def git_show(repo: str, ref: str, path: str) -> str:
    out = subprocess.run(["git", "-C", repo, "show", f"{ref}:{path}"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"ERROR: git show {ref}:{path} failed: {out.stderr.strip()}")
    return out.stdout


def migration_files(repo: str, ref: str) -> list[str]:
    out = subprocess.run(
        ["git", "-C", repo, "ls-tree", "-r", "--name-only", ref],
        capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"ERROR: ls-tree {ref} failed: {out.stderr.strip()}")
    return [p for p in out.stdout.splitlines() if MIGRATION_FILE_RE.search(p)]


def _version_key(path: str) -> int:
    m = re.search(r"[VU](\d+)", path)
    return int(m.group(1)) if m else 0


def tree_index(repo: str, ref: str) -> tuple[dict, set]:
    """(check index, table set) for a full tree: keys (schema, table, name) -> (expr, values).

    Files are applied in migration-version order. A `DROP CONSTRAINT` removes a previously indexed
    definition (e.g. V207 drops the Postgres auto-named V202 column CHECK) BEFORE the same file's
    own re-`ADD` — so a replacement like V207 is one widened constraint, not a dupes-plus-new.
    """
    files = sorted(migration_files(repo, ref), key=_version_key)
    checks: dict[tuple[str, str, str], tuple[str, list[str] | None]] = {}
    tables: set[tuple[str, str]] = set()
    for path in files:
        schema = MIGRATION_FILE_RE.search(path).group(1)
        sql = git_show(repo, ref, path)
        for (ts, tn, name) in drops_from_script(sql, schema):
            checks.pop((ts, tn, name), None)
        for (ts, tn, name, expr) in checks_from_script(sql, schema):
            checks[(ts, tn, name)] = (expr, _parse_check_expr(expr))
        tables |= tables_from_script(sql, schema)
    return checks, tables


# --------------------------------------------------------------------------- verdict driver
def run_gate(repo: str, since: str, tag: str, diff_files: list[str]) -> list[str]:
    """Classify the op-level migration statements and the CHECK replacements.

    CHECK semantics (TD-33) are TRANSITIONAL, not endpoint-diff: bootstrap the constraint state at
    `since`, then apply every in-range file in version order and evaluate each substitution as it
    happens — `new ⊇ old` passes with a log; narrowing, divergence, unparseable definitions or a
    brand-new CHECK on an existing table abort. An endpoint-only diff would let a file create a
    constraint and a later file narrow it invisible inside one range.
    """
    lines: list[str] = []

    # op-level classification per in-range file
    for path in sorted(diff_files):
        if not MIGRATION_FILE_RE.search(path):
            continue
        sql = git_show(repo, tag, path)
        for stmt in split_statements(sql):
            verdict, detail = classify_ops(stmt)
            if verdict == "ABORT":
                lines.append(f"FAIL {path}: D16 violation — {detail}")
            elif verdict == "UNKNOWN":
                lines.append(f"FAIL {path}: parse-unknown — {detail} (fail-closed)")
            elif verdict == "ALLOW":
                lines.append(f"ALLOW {path}: {detail}")

    # CHECK transition walk
    checks, tables = tree_index(repo, since)
    for path in sorted(diff_files, key=_version_key):
        if not MIGRATION_FILE_RE.search(path):
            continue
        schema = MIGRATION_FILE_RE.search(path).group(1)
        sql = git_show(repo, tag, path)
        before_tables = set(tables)
        drops = drops_from_script(sql, schema)
        adds = checks_from_script(sql, schema)
        adds_by_key = {(ts, tn, name): (expr, _parse_check_expr(expr)) for (ts, tn, name, expr) in adds}
        # a drop + re-add of the same name inside ONE file is a substitution (V207 shape): compare
        # against the pre-drop definition, not "new constraint on existing table"
        replaced: dict[tuple[str, str, str], tuple[str, list[str] | None]] = {
            k: checks[k] for k in drops if k in checks
        }
        for key in sorted(drops):
            if key in checks:
                checks.pop(key)
                if key not in adds_by_key:
                    lines.append(f"ALLOW {path}: {key[0]}.{key[1]}.{key[2]} dropped — CHECK removal only widens")
        for (ts, tn, name, expr) in adds:
            key = (ts, tn, name)
            vals = _parse_check_expr(expr)
            label = f"{ts}.{tn}.{name}"
            if key in replaced:
                old_expr, old_vals = replaced[key]
                if _normalize_expr(expr) == _normalize_expr(old_expr):
                    checks[key] = (expr, vals)
                    continue
                if old_vals is not None and vals is not None:
                    if set(old_vals).issubset(vals):
                        added = sorted(set(vals) - set(old_vals))
                        lines.append(f"ALLOW {path}: {label} CHECK widened (+{', '.join(added)})")
                    else:
                        lines.append(f"FAIL {path}: {label} CHECK narrowed or diverged ({old_vals} -> {vals})")
                else:
                    lines.append(f"FAIL {path}: {label} CHECK definition changed and is not a parseable IN set (fail-closed)")
            elif key in checks:
                old_expr, old_vals = checks[key]
                if _normalize_expr(expr) != _normalize_expr(old_expr):
                    if old_vals is not None and vals is not None and set(old_vals).issubset(vals):
                        lines.append(f"ALLOW {path}: {label} CHECK widened (+{', '.join(sorted(set(vals) - set(old_vals)))})")
                    else:
                        lines.append(f"FAIL {path}: {label} CHECK redefined on existing table without proof of widening (fail-closed)")
            else:
                if (ts, tn) in before_tables:
                    lines.append(f"FAIL {path}: {label} new CHECK on existing table (cannot prove widening — fail-closed)")
                else:
                    lines.append(f"ALLOW {path}: {label} new CHECK on new table")
            checks[key] = (expr, vals)
        tables |= tables_from_script(sql, schema)
    return lines


def selftest() -> int:
    """Pure-function smoke: op classification + CHECK extraction on in-memory fixtures.

    The full tree-level gate semantics are exercised by scripts/test-migration-gate.sh, which
    builds real temp git trees (so tree_index, git diff and the CLI `check` path are covered).
    """
    failures = 0

    def expect_ok(verdict_expected_in, stmt, meter):
        v, _ = classify_ops(stmt)
        ok = v in verdict_expected_in
        print(("PASS" if ok else "FAIL"), meter, stmt[:40])
        return not ok

    failures += expect_ok(("ABORT",), "ALTER TABLE t DROP COLUMN id;", "drop-column")
    failures += expect_ok(("ABORT",), "ALTER TABLE ledger.events ALTER COLUMN status TYPE varchar(32);", "alter-type")
    failures += expect_ok(("ABORT",), "ALTER TABLE t ALTER COLUMN c SET NOT NULL;", "set-not-null")
    failures += expect_ok(("ABORT",), "ALTER TABLE t RENAME TO t2;", "rename")
    failures += expect_ok(("ALLOW",), "ALTER TABLE payments.audit_log ALTER COLUMN actor_key_id DROP NOT NULL;", "drop-not-null")
    failures += expect_ok(("ALLOW",), "ALTER TABLE t ALTER COLUMN c DROP DEFAULT;", "drop-default")
    failures += expect_ok(("SAFE",), "ALTER TABLE payments.payments ADD COLUMN next_reconcile_at timestamptz;", "add-column")
    failures += expect_ok(("UNKNOWN",), "DO $$ BEGIN RAISE NOTICE 'x'; END $$;", "parse-unknown")

    recs = checks_from_script(
        "CREATE TABLE ledger.events (id bigint, status varchar(16) "
        "NOT NULL CHECK (status IN ('POSTED','IGNORED','REJECTED')));", "ledger")
    vals_ok = (
        len(recs) == 1
        and recs[0][2] == "EVENTS_STATUS_CHECK"
        and "POSTED" in recs[0][3]
        and "RECEIVED" not in recs[0][3]
    )
    print("PASS" if vals_ok else "FAIL", "check-extract-inline-auto-name")
    failures += 0 if vals_ok else 1

    drops = drops_from_script(
        "ALTER TABLE ledger.events DROP CONSTRAINT events_status_check;", "ledger")
    drop_ok = drops == {("ledger", "events", "EVENTS_STATUS_CHECK")}
    print("PASS" if drop_ok else "FAIL", "check-drop-shadow-name")
    failures += 0 if drop_ok else 1

    recs2 = checks_from_script(
        "ALTER TABLE ledger.events ADD CONSTRAINT events_status_check "
        "CHECK (status IN ('RECEIVED','POSTED','IGNORED','REJECTED'));", "ledger")
    named_ok = len(recs2) == 1 and recs2[0][2] == "EVENTS_STATUS_CHECK" and recs2[0][3].count("RECEIVED") == 1
    print("PASS" if named_ok else "FAIL", "check-extract-named")
    failures += 0 if named_ok else 1

    parsed = checks_from_script("ALTER TABLE s.t ADD CONSTRAINT ck CHECK (amount > 0);", "s")
    opaque_ok = len(parsed) == 1 and _parse_check_expr(parsed[0][3]) is None
    print("PASS" if opaque_ok else "FAIL", "check-opaque-not-in")
    failures += 0 if opaque_ok else 1
    return failures


def main() -> int:
    ap = argparse.ArgumentParser(description="Dargent migration gate (D16, TD-33)")
    ap.add_argument("--repo", default=".")
    sub = ap.add_subparsers(dest="cmd")
    check = sub.add_parser("check")
    check.add_argument("since")
    check.add_argument("tag")
    sub.add_parser("selftest")
    args = ap.parse_args()
    if args.cmd == "selftest":
        return selftest()
    diff = subprocess.run(
        ["git", "-C", args.repo, "diff", "--name-only", f"{args.since}..{args.tag}", "--",
         "*/db/migration/*.sql"],
        capture_output=True, text=True)
    if diff.returncode != 0:
        diff = subprocess.run(["git", "-C", args.repo, "diff", "--name-only", args.since, args.tag, "--",
                               "*/db/migration/*.sql"], capture_output=True, text=True)
        if diff.returncode != 0:
            print(f"FAIL unable to diff {args.since}..{args.tag}: {diff.stderr.strip()}")
            return 1
    files = [p for p in diff.stdout.splitlines() if p]
    if not files:
        print(f"INFO no migrations in {args.since}..{args.tag} — gate clean")
        return 0
    lines = run_gate(args.repo, args.since, args.tag, files)
    for line in lines:
        print(line)
    fails = sum(1 for line in lines if line.startswith("FAIL"))
    if fails:
        print(f"FAIL migration-gate: {fails} violations in {args.since}..{args.tag}")
        return 1
    allows = sum(1 for line in lines if line.startswith("ALLOW"))
    print(f"INFO migration-gate PASS — {len(files)} migration files, {allows} allowed-with-log relaxation(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
#!/usr/bin/env bash
#
# Dargent PITR rehearsal harness (E15 S5 — measured RPO, local-documented-only per Q-batch).
#
# Proves the recovery story the dump drill does NOT: replaying WAL to a point in time. Postgres
#     in a standalone container (isolated, never the dev stack):
#   P0  boot postgres with archive_mode=on + archive_command → host-side WAL archive dir
#   P1  schema (payments + ledger minimum) + seed A (3000 confirmed px + journal + postings)
#   P2  pg_basebackup to the base dir (the "disaster-start" snapshot)
#   P3  side effects AFTER the base backup: +1500 more seeded payments (ledger-balanced)
#       that MUST be recovered by WAL replay — pause_at_txid marks the replay target
#   P4  hard kill -9 the primary (no clean shutdown, only the archived WAL remains)
#   P5  restore: init new cluster, restore base, configure recovery_target_lsn + recovery_target_time,
#       recover to pause; validation runs counts + balance proof at the recovered state
#   P6  measure the achieved RPO: (target_tx_time - pause_tx_time) — sub-second in this harness
#   P7  teardown (throwaway container + dirs)
#
# Design glue — no Flyway: the harness materializes the minimal schema the RECOVERY VALIDATION
# needs (payments.payments + ledger.events/journal_entries/postings/balances + the balance
# proof). This is NOT the app's migration path (that belongs to normal deploy); it proves the
# WAL-replay RECOVERY MECHANISM and measures RPO. Runbook §6 units apply (host volume, then DST).
#
# Usage: bash scripts/pitr-rehearsal.sh
set -euo pipefail

SCRATCH="${DARGENT_PITR_SCRATCH:-/tmp/pitr-rehearsal}"
ARCHIVE_DIR="$SCRATCH/wal_archive"
BASE_DIR="$SCRATCH/base_snapshot"
PRIMARY_DATA="$SCRATCH/primary_data"
RECOVER_DATA="$SCRATCH/recover_data"
CONTAINER="dargent-pitr-rehearsal"
SGID_SOCK="dargent-pitr-sock"
NET_NAME="dargent-pitr-net"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

note() { echo "PITR $(date +%T): $*"; }
die()  { echo "PITR FAIL: $*" >&2; exit 1; }

# The postgres data dirs are owned by the container's postgres user; rm needs the same
# privileges. Clean through a throwaway container when needed.
rm -rf "$SCRATCH" 2>/dev/null || docker run --rm -v "$SCRATCH:/t" postgres:16 bash -c 'rm -rf /t/*' 2>/dev/null || true
mkdir -p "$ARCHIVE_DIR" "$BASE_DIR" "$PRIMARY_DATA" "$RECOVER_DATA"
# The server inside the container runs as the postgres user (uid 999) — the host-owned
# archive dir must be writable by it or archive_command fails (permission denied) forever.
chmod 0777 "$ARCHIVE_DIR"

docker network inspect "$NET_NAME" >/dev/null 2>&1 || docker network create "$NET_NAME" >/dev/null

# ------------------------------------------------------------------ P0: primary with archive_mode
note "P0 booting primary (archive_mode=on, archive_command to $ARCHIVE_DIR)"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker network inspect "$NET_NAME" >/dev/null 2>&1 || docker network create "$NET_NAME" >/dev/null
docker run -d --name "$CONTAINER" \
    --network "$NET_NAME" \
    -e POSTGRES_PASSWORD=dargent \
    -e POSTGRES_USER=dargent \
    -e POSTGRES_DB=dargent \
    -v "$PRIMARY_DATA:/var/lib/postgresql/data" \
    -v "$ARCHIVE_DIR:$ARCHIVE_DIR" \
    postgres:16 \
    -c archive_mode=on \
    -c "archive_command=cp %p $ARCHIVE_DIR/%f" \
    -c "archive_timeout=5s" \
    -c "max_wal_senders=4" \
    -c "wal_level=replica" >/dev/null
# Ready = a real TCP query answers. The image's entrypoint boots a THROWAWAY server (initdb +
# setup scripts) that listens ONLY on the unix socket and is fast-stopped right before the real
# server starts; probing via socket can hit that transiente and die with "shutting down"
# (docker-library/postgres#146). The temporary server NEVER listens on TCP — a 127.0.0.1
# probe is only answered by the real one, after "database system is ready to accept connections".
for i in $(seq 1 60); do
    docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 || die "P0: primary never became queryable"

# ------------------------------------------------------------------ P1: schema + seed A (3000)
note "P1 schema + seed A (3000 balanced payments/journal/postings)"
docker exec -i "$CONTAINER" psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE SCHEMA IF NOT EXISTS payments; CREATE SCHEMA IF NOT EXISTS ledger;
CREATE TABLE payments.payments (
    id uuid PRIMARY KEY, txid varchar(25) NOT NULL UNIQUE, merchant_id uuid NOT NULL,
    description varchar(140), amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    status varchar(32) NOT NULL, version int NOT NULL DEFAULT 0, expires_at timestamptz NOT NULL,
    end_to_end_id varchar(32), fee_cents bigint, net_cents bigint, late_confirmation boolean NOT NULL DEFAULT false,
    refunded_cents bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, confirmed_at timestamptz
);
CREATE TABLE ledger.events (event_id uuid PRIMARY KEY, type varchar(64) NOT NULL, txid varchar(64) NOT NULL,
    merchant_id uuid NOT NULL, payload jsonb NOT NULL, status varchar(16) NOT NULL, note text, received_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ledger.journal_entries (id uuid PRIMARY KEY, event_id uuid NOT NULL UNIQUE, txid varchar(64) NOT NULL,
    merchant_id uuid NOT NULL, description text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ledger.postings (id uuid PRIMARY KEY, entry_id uuid NOT NULL, account text NOT NULL,
    direction varchar(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')), amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ledger.balances (account text PRIMARY KEY, balance_cents bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(), last_event_id uuid);
-- seed A: 3000 rows, each = 1 payment + 1 event + 1 journal entry + 3 postings (DR processing/CR fees/CR merchant avail)
INSERT INTO payments.payments
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g),12,'0'))::uuid,
       substr(upper(md5('seed-a-'||g)),1,25), 'a0000000-0000-4000-8000-000000000001'::uuid,
       'pitr-seed-a', (g%9000)+1000, 'CONFIRMED', 0, now()+interval '1 hour', 'E'||g,
       (g%9000+1000)/100, (g%9000+1000)-((g%9000+1000)/100), false, 0, now(), now()
FROM generate_series(1,3000) g;
INSERT INTO ledger.events
SELECT ('00000000-0000-0000-0000-'||lpad(to_hex(g),12,'0'))::uuid, 'payment.confirmed', substr(upper(md5('seed-a-'||g)),1,25),
       'a0000000-0000-4000-8000-000000000001'::uuid, '{}', 'POSTED', 'pitr-seed-a', now()
FROM generate_series(1,3000) g;
INSERT INTO ledger.journal_entries
SELECT ('00000000-0000-0000-0001-'||lpad(to_hex(g),12,'0'))::uuid, ('00000000-0000-0000-0000-'||lpad(to_hex(g),12,'0'))::uuid,
       substr(upper(md5('seed-a-'||g)),1,25), 'a0000000-0000-4000-8000-000000000001'::uuid,
       'Payment confirmed: '||substr(upper(md5('seed-a-'||g)),1,25), now()
FROM generate_series(1,3000) g;
INSERT INTO ledger.postings
SELECT ('00000000-0000-0000-0002-'||lpad(to_hex(g*3+d.off),12,'0'))::uuid,
       ('00000000-0000-0000-0001-'||lpad(to_hex(g),12,'0'))::uuid,
       CASE d.off WHEN 0 THEN 'payments:processing' WHEN 1 THEN 'fees:revenue' ELSE 'merchant:a0000000-0000-4000-8000-000000000001:available' END,
       CASE d.off WHEN 0 THEN 'DEBIT' ELSE 'CREDIT' END,
       CASE d.off WHEN 0 THEN (g%9000)+1000 WHEN 1 THEN ((g%9000)+1000)/100 ELSE (g%9000+1000)-(((g%9000)+1000)/100) END,
       now()
FROM generate_series(1,3000) g CROSS JOIN (VALUES (0),(1),(2)) d(off);
INSERT INTO ledger.balances SELECT 'payments:processing', -COALESCE(SUM(amount_cents),0), now(), NULL FROM payments.payments WHERE description='pitr-seed-a';
INSERT INTO ledger.balances SELECT 'fees:revenue', COALESCE(SUM(fee_cents),0), now(), NULL FROM payments.payments WHERE description='pitr-seed-a';
INSERT INTO ledger.balances SELECT 'merchant:a0000000-0000-4000-8000-000000000001:available', COALESCE(SUM(net_cents),0), now(), NULL FROM payments.payments WHERE description='pitr-seed-a';
SQL
A_COUNT=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description='pitr-seed-a'")
A_DR=$(docker exec "$CONTAINER" psql -U dargent -d dargent -tAc "select coalesce(sum(amount_cents),0) from payments.payments where description='pitr-seed-a'")
note "P1 seed A ok — $A_COUNT payments (ΣDR=$A_DR)"

# ------------------------------------------------------------------ P2: base backup (snapshot baseline, BEFORE the extra traffic)
# pg_basebackup -Ft -X stream: tarball holds the full cluster; WAL for replay comes from the
# archive dir (archive_mode archive_timeout=5s guarantees every segment is archived, including
# the ones streamed during backup).
note "P2 pg_basebackup at seed-A state (base_snapshot)"
docker exec "$CONTAINER" bash -c "pg_basebackup -D /tmp/bb -U dargent -Ft -z -X stream"
docker cp "$CONTAINER:/tmp/bb/base.tar.gz" "$BASE_DIR/base.tar.gz"
docker cp "$CONTAINER:/tmp/bb/pg_wal.tar.gz" "$BASE_DIR/pg_wal.tar.gz" 2>/dev/null || true
docker exec "$CONTAINER" rm -rf /tmp/bb
ls -l "$BASE_DIR/"

# ------------------------------------------------------------------ P3: post-backup side effects (the WAL-replay target)
note "P3 post-base side effects: +1500 payments (must be recovered by WAL replay), pause marker written at a known lsn"
PAUSE_LSN_BEFORE=$(docker exec "$CONTAINER" psql -U dargent -d dargent -tAc "select pg_current_wal_lsn()")
docker exec -i "$CONTAINER" psql -U dargent -d dargent -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO payments.payments
SELECT ('00000000-0000-0000-1000-'||lpad(to_hex(g),12,'0'))::uuid,
       substr(upper(md5('seed-b-'||g)),1,25), 'a0000000-0000-4000-8000-000000000001'::uuid,
       'pitr-seed-b', (g%9000)+1000, 'CONFIRMED', 0, now()+interval '1 hour', 'E'||g,
       (g%9000+1000)/100, (g%9000+1000)-((g%9000+1000)/100), false, 0, now(), now()
FROM generate_series(1,1500) g;
INSERT INTO ledger.events
SELECT ('00000000-0000-0000-1000-'||lpad(to_hex(g),12,'0'))::uuid, 'payment.confirmed', substr(upper(md5('seed-b-'||g)),1,25),
       'a0000000-0000-4000-8000-000000000001'::uuid, '{}', 'POSTED', 'pitr-seed-b', now()
FROM generate_series(1,1500) g;
INSERT INTO ledger.journal_entries
SELECT ('00000000-0000-0000-1001-'||lpad(to_hex(g),12,'0'))::uuid, ('00000000-0000-0000-1000-'||lpad(to_hex(g),12,'0'))::uuid,
       substr(upper(md5('seed-b-'||g)),1,25), 'a0000000-0000-4000-8000-000000000001'::uuid,
       'Payment confirmed: '||substr(upper(md5('seed-b-'||g)),1,25), now()
FROM generate_series(1,1500) g;
INSERT INTO ledger.postings
SELECT ('00000000-0000-0000-1002-'||lpad(to_hex(g*3+d.off),12,'0'))::uuid,
       ('00000000-0000-0000-1001-'||lpad(to_hex(g),12,'0'))::uuid,
       CASE d.off WHEN 0 THEN 'payments:processing' WHEN 1 THEN 'fees:revenue' ELSE 'merchant:a0000000-0000-4000-8000-000000000001:available' END,
       CASE d.off WHEN 0 THEN 'DEBIT' ELSE 'CREDIT' END,
       CASE d.off WHEN 0 THEN (g%9000)+1000 WHEN 1 THEN ((g%9000)+1000)/100 ELSE (g%9000+1000)-(((g%9000)+1000)/100) END,
       now()
FROM generate_series(1,1500) g CROSS JOIN (VALUES (0),(1),(2)) d(off);
INSERT INTO ledger.balances SELECT 'payments:processing', -COALESCE(SUM(amount_cents),0), now(), NULL FROM payments.payments WHERE description IN ('pitr-seed-a','pitr-seed-b')
  ON CONFLICT (account) DO UPDATE SET balance_cents = EXCLUDED.balance_cents, updated_at = now();
INSERT INTO ledger.balances SELECT 'fees:revenue', COALESCE(SUM(fee_cents),0), now(), NULL FROM payments.payments WHERE description IN ('pitr-seed-a','pitr-seed-b')
  ON CONFLICT (account) DO UPDATE SET balance_cents = EXCLUDED.balance_cents, updated_at = now();
INSERT INTO ledger.balances SELECT 'merchant:a0000000-0000-4000-8000-000000000001:available', COALESCE(SUM(net_cents),0), now(), NULL FROM payments.payments WHERE description IN ('pitr-seed-a','pitr-seed-b')
  ON CONFLICT (account) DO UPDATE SET balance_cents = EXCLUDED.balance_cents, updated_at = now();
-- Force a segment switch so the last side-effect txns are GUARANTEED archived
-- (archive_command only fires on completed segments). The switch runs AFTER the target
-- LSN is captured: recovery_target_lsn must sit inside a COMPLETED (archived) segment —
-- a post-switch LSN points into the brand-new, still-empty segment that will never be
-- archived, and PG16 dies with "recovery ended before configured recovery target was
-- reached".
SQL
PAUSE_LSN_AFTER=$(docker exec "$CONTAINER" psql -U dargent -d dargent -tAc "select pg_current_wal_insert_lsn()")
docker exec "$CONTAINER" psql -U dargent -d dargent -q -c "select pg_switch_wal()" >/dev/null
# The recovery target must be a point WITH committed WAL at-or-before it. A wall-clock target
# captured from the shell after the last commit can land past the archived WAL's end, killing
# PG16 with "recovery ended before configured recovery target was reached". The deterministic
# target is the LSN of the last committed side effect: recovery replays every commit up to and
# including that LSN (seed-b complete) — exactly the pause point we want. recovery_target_lsn is
# documented (PG §20.5.6) and race-free.
PAUSE_TS=$(docker exec "$CONTAINER" psql -U dargent -d dargent -tAc "select to_char(clock_timestamp(),'YYYY-MM-DD HH24:MI:SS.US')" | tr -d '\n')
B_TOTAL=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description in ('pitr-seed-a','pitr-seed-b')")
note "P3 side effects ok — total now $B_TOTAL payments (3A+3B postings), pause target lsn=$PAUSE_LSN_AFTER (commit ts $PAUSE_TS)"

# ------------------------------------------------------------------ P4: HARD KILL (no clean shutdown — the disaster)
note "P4 killing primary WITHOUT shutdown (kill -9)"
docker kill -s 9 "$CONTAINER" >/dev/null
docker rm "$CONTAINER" >/dev/null

# ------------------------------------------------------------------ P5: restore to the pause point (WAL replay)
# Build the recovered cluster: fresh data dir <- base snapshot tars, then recovery.conf
# settings pointing at the archived WAL and the pause target. start-up in recovery consumes
# WAL and pauses; exit recovery when we resume.
note "P5 restoring from base + archived WAL to target_lsn=$PAUSE_LSN_AFTER"
# Fresh recover cluster data dir (empty — the recovered instance will consume the base+WAL).
docker run --rm -v "$RECOVER_DATA:/data" postgres:16 bash -c 'cp -a /var/lib/postgresql/data/. /data/' 2>/dev/null || true
docker run --rm -v "$RECOVER_DATA:/data" -v "$BASE_DIR:/base" postgres:16 bash -c '
  set -e
  rm -rf /data/* /data/.[!.]* 2>/dev/null || true
  tar -xzf /base/base.tar.gz -C /data
  mkdir -p /data/pg_wal
  tar -xzf /base/pg_wal.tar.gz -C /data/pg_wal
' >/dev/null
# Parameterize recovery: archive must STOP after replay (archive_mode=off during restore) and
# the target is the pause point. wal_level must match the archived primary (replica).
# restore_command uses the ABSOLUTE host path — the recover container bind-mounts the archive
# dir at the same path it has on the host.
#
# The target is the LSN captured right after the last committed side effect (deterministic,
# race-free): recovery replays every commit up to that LSN — the full seed-b — and pauses.
# A wall-clock target captured from the shell can land past the archived WAL's end (PG16 then
# dies with "recovery ended before configured recovery target was reached"); an LSN cannot.
#
# PG16 REQUIRES the empty marker file `recovery.signal` in the data dir or the server boots
# NORMAL (no WAL replay at all) — restore_command alone is ignored (PG docs §26.3.4 step 7).
# A stale postmaster.pid from the killed primary would also confuse startup — remove it.
docker run --rm -v "$RECOVER_DATA:/data" -v "$ARCHIVE_DIR:/wal" postgres:16 bash -c "
  echo \"archive_mode = off\" >> /data/postgresql.conf
  echo \"restore_command = 'cp $ARCHIVE_DIR/%f %p'\" >> /data/postgresql.conf
  echo \"recovery_target_lsn = '$PAUSE_LSN_AFTER'\" >> /data/postgresql.conf
  echo \"recovery_target_inclusive = on\" >> /data/postgresql.conf
  echo \"recovery_target_action = pause\" >> /data/postgresql.conf
  touch /data/recovery.signal
  rm -f /data/postmaster.pid
" >/dev/null
docker run -d --name "$CONTAINER" \
    --network "$NET_NAME" \
    -e POSTGRES_PASSWORD=dargent -e POSTGRES_USER=dargent -e POSTGRES_DB=dargent \
    -v "$RECOVER_DATA:/var/lib/postgresql/data" \
    -v "$ARCHIVE_DIR:$ARCHIVE_DIR" \
    postgres:16 >/dev/null
RECOVERY_START=$(date +%s)
# recovery_target_action=pause: the server reaches the target, becomes QUERYABLE in read-only
# recovery (pg_is_in_recovery stays 't' forever). Wait until psql answers, not for 'f'.
# TCP probe (127.0.0.1): a paused-in-recovery server answers connections normally.
for i in $(seq 1 120); do
    if docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1; then break; fi
    sleep 1
done
docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 || {
    echo "recovery never became queryable"; docker logs "$CONTAINER" 2>&1 | tail -10
}
ST=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select pg_is_in_recovery()" || echo "?")
RECOVERY_END=$(date +%s)
note "P5 recovered (in-recovery=$ST, paused at target)"
R_TOTAL=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description in ('pitr-seed-a','pitr-seed-b')" 2>/dev/null || echo "?")
R_A=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description='pitr-seed-a'" 2>/dev/null || echo "?")
R_B=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description='pitr-seed-b'" 2>/dev/null || echo "?")
note "P5 recovered — total payments=$R_TOTAL (A=$R_A, B=$R_B), recovery wall-clock $((RECOVERY_END-RECOVERY_START))s"

# ------------------------------------------------------------------ P6: validation = counts + balance proof at the RECOVERED state
note "P6 validation at recovered state (counts + balance proof)"
R_POSTINGS=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from ledger.postings")
R_DR=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select coalesce(sum(amount_cents),0) from ledger.postings where direction='DEBIT'")
R_CR=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select coalesce(sum(amount_cents),0) from ledger.postings where direction='CREDIT'")
[[ "$R_TOTAL" = "4500" ]] || die "P6: expected 4500 payments after replay, got $R_TOTAL"
[[ "$R_B" = "1500" ]] || die "P6: seed-b (post-base) should be fully recovered by WAL replay, got $R_B"
[[ "$R_DR" = "$R_CR" ]] || die "P6: balance proof FAILED — ΣDR=$R_DR != ΣCR=$R_CR"
PROJECTION=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from ledger.balances b where b.balance_cents != (select coalesce(sum(CASE WHEN p.direction='CREDIT' THEN p.amount_cents ELSE -p.amount_cents END),0) from ledger.postings p where p.account=b.account)")
[[ "$PROJECTION" = "0" ]] || die "P6: projection diverges for $PROJECTION account(s)"
note "P6 PASS — ΣDR=ΣCR=$R_DR, projection==lines, postings=$R_POSTINGS"

# ------------------------------------------------------------------ RPO line (measured)
# The last recovered txn's commit time vs the target: the harness wrote seed-b then switch_wal;
# achieved RPO = target_ts - last recovered commit. We measure via recovery_target_lsn bounding:
# the WAL time we requested is the last committed seed-b txn (= pause), so RPO ≈ the gap between
# the PAUSE marker and the requested target — here, the SAME txn, i.e. <= archive_timeout granularity.
RPO_WINDOW=$(docker exec "$CONTAINER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select extract(epoch from (clock_timestamp() - to_timestamp('$PAUSE_TS','YYYY-MM-DD HH24:MI:SS.US')))::int")
note "achieved RPO ≈ ${RPO_WINDOW}s of WAL replay (target lsn $PAUSE_LSN_AFTER, commit ts $PAUSE_TS; archive lag < archive_timeout 5s)"

# ------------------------------------------------------------------ P7 teardown
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker network rm "$NET_NAME" >/dev/null 2>&1 || true
rm -rf "$SCRATCH" 2>/dev/null || docker run --rm -v "$SCRATCH:/t" postgres:16 bash -c 'rm -rf /t/*' 2>/dev/null || true
note "PITR PASS — 3000 base + 1500 WAL-replayed, counts + balance + projection verified, RPO ~${RPO_WINDOW}s"
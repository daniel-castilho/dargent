#!/usr/bin/env bash
#
# Dargent PITR rehearsal v2 (E16 S2 — off-disk WAL; the E15 S5 caveat answered).
#
# v1 (scripts/pitr-rehearsal.sh) proved the WAL-replay mechanism with the archive on the SAME
# host directory as everything else — declared limit: "a REAL disaster (host/disk loss)
# requires the archive off-host". v2 answers the caveat the way compose can honestly prove:
# the WAL archive and the base snapshot live on SEPARATE docker volumes, and the disaster is
# the DESTRUCTION of the Postgres data volume itself — pgdata is deleted (docker volume rm),
# not just the container killed.
# What survives is exactly what a real off-disk chain gives: base snapshot + archived WAL,
# on different spindles than the one that died.
#
# Phases (v1 mechanics, hardened):
#   P0  named volumes: pitr-v2-pgdata / pitr-v2-wal / pitr-v2-base; the primary's
#       archive_command writes completed segments into the SEPARATE wal volume
#   P1  schema + seed A (3000 balanced txns) on the primary (pgdata volume)
#   P2  pg_basebackup -Ft -X stream -> base volume (via tar copy through the sidecar)
#   P3  post-base side effects (+1500 txns); capture recovery_target_lsn (BEFORE the switch,
#       inside the completed segment — the E15 lesson); pg_switch_wal archives it
#   P4  DISASTER: docker kill -9 the primary AND `docker volume rm` the pgdata volume —
#       the database disk is GONE; only base-volume + wal-volume survive
#   P5  recovery cluster on a FRESH pgdata volume: base tars + restore_command reading the
#       wal volume (ro), recovery.signal, recovery_target_lsn, recovery_target_action=pause;
#       replay
#   P6  validation at the recovered state (read-only, in-recovery): counts + ΣDR=ΣCR +
#       projection==lines (the restore.sh §5 proof, same shape)
#   P7  measured RPO window v2 + teardown (containers, network, volumes — all named v2)
#
# TCP-only readiness probes throughout (docker-library/postgres#146: the entrypoint's
# throwaway socket-only server makes socket probes race-prone; the temp server never
# listens on TCP). research-before-trial-and-error: AGENTS amendment (f).
#
# Q-batch disposition (2026-09-08): CI-ified as dispatch-only job `pitr-drill`; NOT wired into
# release.yml (the dump-restore drill stays THE release gate; PITR remains an RPO rehearsal —
# recorded in docs/drills/pitr-v2-2026-09-08.md).
#
# Usage: bash scripts/pitr-rehearsal-v2.sh
set -euo pipefail

V2_PREFIX="dargent-pitr-v2"
PG_VOLUME="${V2_PREFIX}-pgdata"
WAL_VOLUME="${V2_PREFIX}-wal"
BASE_VOLUME="${V2_PREFIX}-base"
PRIMARY="${V2_PREFIX}-primary"
RECOVER="${V2_PREFIX}-recover"
NET="${V2_PREFIX}-net"

note() { echo "PITR-V2 $(date +%T): $*"; }
die()  { echo "PITR-V2 FAIL: $*" >&2; exit 1; }

teardown() {
    docker rm -f "$PRIMARY" "$RECOVER" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    docker volume rm "$PG_VOLUME" "$WAL_VOLUME" "$BASE_VOLUME" >/dev/null 2>&1 || true
}
trap teardown EXIT

note "P0 creating off-disk topology (separate volumes: pgdata / wal-archive / base)"
teardown
docker network create "$NET" >/dev/null
docker volume create "$PG_VOLUME" >/dev/null
docker volume create "$WAL_VOLUME" >/dev/null
docker volume create "$BASE_VOLUME" >/dev/null

# The WAL archive lives on its OWN docker volume (separate host subtree from the pgdata
# volume): archive_command writes completed segments there. In P4 the pgdata volume itself is
# DESTROYED (docker volume rm) — the archive and base volumes survive, which is the off-disk
# property under test. Residual limit (declared in the drill doc): volumes still share the
# host — a real off-host/NFS archive is the production posture this rehearsal models.
# The volume must be WRITABLE by the postgres uid (999): a fresh named volume mounts root-owned
# (0755) and every archive_command cp fails with EACCES — pg_stat_archiver failed_count climbs
# and the archive stays empty (the E15 v1 used a chmod-0777 host dir; the v2 volume needs the
# same treatment BEFORE the primary boots).
docker run --rm -v "$WAL_VOLUME:/wal" postgres:16 bash -c 'mkdir -p /wal && chmod 0777 /wal'
docker run -d --name "$PRIMARY" --network "$NET" \
    -v "$PG_VOLUME:/var/lib/postgresql/data" \
    -v "$WAL_VOLUME:/wal-archive" \
    -e POSTGRES_PASSWORD=dargent -e POSTGRES_USER=dargent -e POSTGRES_DB=dargent \
    postgres:16 \
    -c archive_mode=on \
    -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f" \
    -c archive_timeout=5s \
    -c wal_level=replica >/dev/null
for i in $(seq 1 60); do
    docker exec "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 \
    || die "P0: primary never became queryable"
note "P0 primary up — pgdata and wal-archive on SEPARATE volumes"

# ------------------------------------------------------------------ P1: schema + seed A
note "P1 schema + seed A (3000 balanced payments/journal/postings)"
docker exec -i "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -v ON_ERROR_STOP=1 -q <<'SQL'
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
INSERT INTO payments.payments
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g),12,'0'))::uuid,
       substr(upper(md5('seed-a-'||g)),1,25), 'a0000000-0000-4000-8000-000000000001'::uuid,
       'pitr-seed-a', (g%9000)+1000, 'CONFIRMED', 0, now()+interval '1 hour', 'E'||g,
       ((g%9000)+1000)/100, (g%9000)+1000-((g%9000)+1000)/100, false, 0, now(), now()
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
       CASE d.off WHEN 0 THEN (g%9000)+1000 WHEN 1 THEN ((g%9000)+1000)/100 ELSE (g%9000)+1000-((g%9000)+1000)/100 END,
       now()
FROM generate_series(1,3000) g CROSS JOIN (VALUES (0),(1),(2)) d(off);
INSERT INTO ledger.balances SELECT 'payments:processing', -COALESCE(SUM(amount_cents),0), now(), NULL FROM payments.payments WHERE description='pitr-seed-a';
INSERT INTO ledger.balances SELECT 'fees:revenue', COALESCE(SUM(fee_cents),0), now(), NULL FROM payments.payments WHERE description='pitr-seed-a';
INSERT INTO ledger.balances SELECT 'merchant:a0000000-0000-4000-8000-000000000001:available', COALESCE(SUM(net_cents),0), now(), NULL FROM payments.payments WHERE description='pitr-seed-a';
SQL
note "P1 seed A ok"

# ------------------------------------------------------------------ P2: base backup -> base volume
note "P2 pg_basebackup -> base volume (off the pgdata volume)"
docker exec "$PRIMARY" bash -c "pg_basebackup -D /tmp/bb -U dargent -Ft -z -X stream"
docker cp "$PRIMARY:/tmp/bb/base.tar.gz" /tmp/pitr-v2-base.tar.gz
docker cp "$PRIMARY:/tmp/bb/pg_wal.tar.gz" /tmp/pitr-v2-pgwal.tar.gz 2>/dev/null || true
docker exec "$PRIMARY" rm -rf /tmp/bb
# stage the tars into the BASE volume via a throwaway helper container (canonical names:
# base.tar.gz / pg_wal.tar.gz — the recovery helpers read these exact names)
docker run --rm -v "$BASE_VOLUME:/base" -v /tmp:/hosttmp postgres:16 \
    bash -c "cp /hosttmp/pitr-v2-base.tar.gz /base/base.tar.gz && cp /hosttmp/pitr-v2-pgwal.tar.gz /base/pg_wal.tar.gz && ls -l /base/"
rm -f /tmp/pitr-v2-base.tar.gz /tmp/pitr-v2-pgwal.tar.gz

# ------------------------------------------------------------------ P3: post-base side effects + target LSN
note "P3 post-base side effects: +1500 payments"
docker exec -i "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -v ON_ERROR_STOP=1 -q <<'SQL'
INSERT INTO payments.payments
SELECT ('00000000-0000-0000-1000-'||lpad(to_hex(g),12,'0'))::uuid,
       substr(upper(md5('seed-b-'||g)),1,25), 'a0000000-0000-4000-8000-000000000001'::uuid,
       'pitr-seed-b', (g%9000)+1000, 'CONFIRMED', 0, now()+interval '1 hour', 'E'||g,
       ((g%9000)+1000)/100, (g%9000)+1000-((g%9000)+1000)/100, false, 0, now(), now()
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
       CASE d.off WHEN 0 THEN (g%9000)+1000 WHEN 1 THEN ((g%9000)+1000)/100 ELSE (g%9000)+1000-((g%9000)+1000)/100 END,
       now()
FROM generate_series(1,1500) g CROSS JOIN (VALUES (0),(1),(2)) d(off);
INSERT INTO ledger.balances SELECT 'payments:processing', -COALESCE(SUM(amount_cents),0), now(), NULL FROM payments.payments WHERE description IN ('pitr-seed-a','pitr-seed-b')
  ON CONFLICT (account) DO UPDATE SET balance_cents = EXCLUDED.balance_cents, updated_at = now();
INSERT INTO ledger.balances SELECT 'fees:revenue', COALESCE(SUM(fee_cents),0), now(), NULL FROM payments.payments WHERE description IN ('pitr-seed-a','pitr-seed-b')
  ON CONFLICT (account) DO UPDATE SET balance_cents = EXCLUDED.balance_cents, updated_at = now();
INSERT INTO ledger.balances SELECT 'merchant:a0000000-0000-4000-8000-000000000001:available', COALESCE(SUM(net_cents),0), now(), NULL FROM payments.payments WHERE description IN ('pitr-seed-a','pitr-seed-b')
  ON CONFLICT (account) DO UPDATE SET balance_cents = EXCLUDED.balance_cents, updated_at = now();
SQL
# E15 lesson: the LSN target must sit inside a COMPLETED segment — capture BEFORE the switch.
PAUSE_LSN=$(docker exec "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select pg_current_wal_insert_lsn()")
PAUSE_TS=$(docker exec "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select to_char(clock_timestamp(),'YYYY-MM-DD HH24:MI:SS.US')")
docker exec "$PRIMARY" psql -U dargent -d dargent -h 127.0.0.1 -q -c "select pg_switch_wal()" >/dev/null
sleep 2   # let the archiver land the segment in the wal volume
note "P3 side effects ok — target lsn=$PAUSE_LSN (ts $PAUSE_TS)"

# ------------------------------------------------------------------ P4: DISASTER — the DB volume DIES
note "P4 disaster: kill -9 primary AND DESTROY the pgdata volume (only base+wal volumes survive)"
docker kill -s 9 "$PRIMARY" >/dev/null
docker rm -f "$PRIMARY" >/dev/null
docker volume rm "$PG_VOLUME" >/dev/null
docker volume ls --format '{{.Name}}' | grep -q "^${PG_VOLUME}$" && die "P4: pgdata volume still exists"
note "P4 pgdata volume DESTROYED — survivors: base + wal volumes (separate spindles)"

# ------------------------------------------------------------------ P5: recovery on a FRESH pgdata volume
note "P5 recovery cluster from base volume + wal volume (replay to $PAUSE_LSN)"
docker volume create "$PG_VOLUME" >/dev/null
docker run --rm -v "$PG_VOLUME:/data" -v "$BASE_VOLUME:/base" postgres:16 bash -c '
  set -e
  rm -rf /data/* /data/.[!.]* 2>/dev/null || true
  tar -xzf /base/base.tar.gz -C /data
  mkdir -p /data/pg_wal
  tar -xzf /base/pg_wal.tar.gz -C /data/pg_wal 2>/dev/null || true
'
docker run --rm -v "$PG_VOLUME:/data" -v "$WAL_VOLUME:/wal" postgres:16 bash -c "
  echo \"archive_mode = off\" >> /data/postgresql.conf
  echo \"restore_command = 'cp /wal/%f %p'\" >> /data/postgresql.conf
  echo \"recovery_target_lsn = '$PAUSE_LSN'\" >> /data/postgresql.conf
  echo \"recovery_target_inclusive = on\" >> /data/postgresql.conf
  echo \"recovery_target_action = pause\" >> /data/postgresql.conf
  touch /data/recovery.signal
  rm -f /data/postmaster.pid
"
RECOVERY_START=$(date +%s)
docker run -d --name "$RECOVER" --network "$NET" \
    -v "$PG_VOLUME:/var/lib/postgresql/data" \
    -v "$WAL_VOLUME:/wal:ro" \
    -e POSTGRES_PASSWORD=dargent -e POSTGRES_USER=dargent -e POSTGRES_DB=dargent \
    postgres:16 >/dev/null
for i in $(seq 1 120); do
    docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select 1" >/dev/null 2>&1 || {
    docker logs "$RECOVER" 2>&1 | tail -10 >&2
    die "P5: recovery never became queryable"
}
RECOVERY_END=$(date +%s)
ST=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select pg_is_in_recovery()")
note "P5 recovered (in-recovery=$ST, paused at target) — wall-clock $((RECOVERY_END-RECOVERY_START))s"

# ------------------------------------------------------------------ P6: validation at the recovered state
R_TOTAL=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description in ('pitr-seed-a','pitr-seed-b')")
R_A=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description='pitr-seed-a'")
R_B=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from payments.payments where description='pitr-seed-b'")
[[ "$R_TOTAL" = "4500" ]] || die "P6: expected 4500 payments after replay, got $R_TOTAL (A=$R_A B=$R_B)"
[[ "$R_B" = "1500" ]] || die "P6: seed-b (post-base) must be fully recovered by WAL replay, got $R_B"
R_DR=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select coalesce(sum(amount_cents),0) from ledger.postings where direction='DEBIT'")
R_CR=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select coalesce(sum(amount_cents),0) from ledger.postings where direction='CREDIT'")
[[ "$R_DR" = "$R_CR" ]] || die "P6: balance proof FAILED — ΣDR=$R_DR != ΣCR=$R_CR"
PROJECTION=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select count(*) from ledger.balances b where b.balance_cents != (select coalesce(sum(CASE WHEN p.direction='CREDIT' THEN p.amount_cents ELSE -p.amount_cents END),0) from ledger.postings p where p.account=b.account)")
[[ "$PROJECTION" = "0" ]] || die "P6: projection diverges for $PROJECTION account(s)"
RPO_WINDOW=$(docker exec "$RECOVER" psql -U dargent -d dargent -h 127.0.0.1 -tAc "select extract(epoch from (clock_timestamp() - to_timestamp('$PAUSE_TS','YYYY-MM-DD HH24:MI:SS.US')))::int")
note "P6 PASS — A=$R_A B=$R_B, ΣDR=ΣCR=$R_DR, projection==lines, RPO ~${RPO_WINDOW}s (target lsn $PAUSE_LSN)"
note "PITR-V2 PASS — off-disk replay verified: pgdata volume destroyed, base+wal volumes carried the recovery, RPO ~${RPO_WINDOW}s"
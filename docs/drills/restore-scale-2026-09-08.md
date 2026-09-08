# Scale restore drill — 2026-09-08 (E15 S4)

The restore machinery proven at 36 KB in E14 S4 (3 txns) is re-proven **at volume**. Same
script (`scripts/ci-restore-drill.sh`), same mechanics, one addition: `DRILL_SCALE_SEED_TXNS`
bulk-seeds the journal via SQL (D1b) before backup → destroy → restore.

## Q-batch decisions (2026-09-08)

- **Volume:** 20 000 CONFIRMED payments, one POSTED `payment.confirmed` event + one journal
  entry + 3 postings each → **20 000 journal entries + 60 000 postings** (ledger lines
  10× the S4 minimum class of 10k). Note: S4's own backlog range is "10k–100k journal lines /
  txns" — we banked 80k journal lines (20k entries + 60k postings), comfortably mid-range.
  CI runs the same seed with `DRILL_SCALE_SEED_TXNS=50000` (80k→200k lines) — dispatch-only.
- **Seed integrity (DEBT-5 barrier, honest):** every seeded payment mirrors the consumer's real
  posting shape (DR `payments:processing` = amount, CR `fees:revenue` = fee, CR
  `merchant:…:available` = net), balances projection updated, `ΣDR = ΣCR` — all re-proven by
  `restore.sh` §5 (counts match manifest, balanced, projection == lines). The seed is a *fixture*,
  not a bypass: the restore proof treats it as data.

## Run record (local, 2026-09-08 09:07 UTC)

| Metric | Value |
|---|---|
| Date / env | 2026-09-08, local bare-metal (same host class as CI runner) |
| Stack | isolated compose project `dargent-drill` (ports 18xxx), dev stack untouched |
| Seeded | 20 000 payments + 20 000 POSTED events + 20 000 journal_entries + 60 000 postings |
| API-created realism txns | 3 (smoke money path, before backup) |
| Backup | `dargent-20260908-090708.dump` — **2.28 MB** (`dumpSizeBytes` 2 278 847) |
| Manifest | payments=20003, journal_entries=20000, postings=60000, events=20000 |
| Ledger sums | ΣDR = ΣCR = **102 992 000** cents, `balanced=true` |
| **Measured RTO** | **21 s** (budget ≤ 30 min = 1800 s) |
| Verify | counts match manifest, ΣDR=ΣCR, projection == lines → **GO** |
| Post-restore | cluster serves NEW traffic: 1 fresh smoke txn CONFIRMED on restored stack |
| Outcome | `DRILL RESULT: PASS — RTO 21s` |

## The "36 KB seed" answer — honest delta

| Drill | Seed | Backup size | Measured RTO |
|---|---|---|---|
| E14 S4 (`restore-2026-09-07.md`) | 3 API txns | 36 KB | ~20 s |
| E15 S4 (this record) | 20 000 txns (80k journal lines) | 2.28 MB | **21 s** |

RTO is essentially flat: restore cost is dominated by boot + `pg_restore` fixed overhead, not
by row count in this volume class. **Measured, not extrapolated** — the 20k run's RTO is 21 s;
the larger class (200k lines, CI dispatch `restore-drill-scale`) gets its own measured line the
moment the dispatch runs, with this record's methodology. No claim is made above the measured
point.

## Artifact-reality caveat (kept, per S3-adjacent honesty)

The bulk seed is a **fixture** written as the app writes (same posting shape, balanced by
construction). A from-scratch migration replay at this volume is a different exercise and is out
of scope here — this drill proves *backup/restore of a full ledger DB at volume*, which is the
E14-S6.5 question this record answers.

## Reproduction

```bash
DRILL_SCALE_SEED_TXNS=20000 bash scripts/ci-restore-drill.sh
# CI: dispatch `restore-drill-scale` (workflow_dispatch) → seed 50000, same script
```

## Evidence (verbatim)

`scripts/ci-restore-drill.sh` D1b→D4 lines from the passing run:

```
DRILL D1b bulk-seeding 20000 CONFIRMED payments + journal entries + postings (SQL)
DRILL D1b seed ok — payments/journal/postings/events = 20000/20000/60000/20000
BACKUP manifest written: dargent-20260908-090708.manifest.json (ΣDR=102992000 ΣCR=102992000)
DRILL D2 backup ok — dargent-20260908-090708.dump (2278847 bytes), manifest: payments=20003 ΣDR=102992000 ΣCR=102992000
GO/NO-GO: GO — restore verified (counts match manifest, ΣDR=ΣCR=102992000, projection==lines) in 21s
DRILL D4 restore VERIFIED — measured RTO 21s (budget 1800s)
DRILL RESULT: PASS — 3 API txns + 20000 scale-seeded txns, backup→destroy→restore verified, RTO 21s
```

## CI dispatch record (the official at-scale number)

Run `34244399134` (workflow_dispatch on main, 2026-09-08 15:34–15:36 UTC), job
`restore-drill-scale` — **SUCCESS** with `DRILL_SCALE_SEED_TXNS=50000`:

| Metric | Value |
|---|---|
| Seeded | **50 000** payments + 50 000 POSTED events + 50 000 journal_entries + **150 000 postings** (200k ledger lines) |
| Manifest | payments=50 003 (50k seed + 3 API smoke), ΣDR=ΣCR=**264 980 000** cents, balanced |
| Backup | `dargent-20260908-153540.dump` — **5.6 MB** (5 635 696 bytes) |
| **Measured RTO at scale** | **23 s** (budget ≤ 1800 s) — GO/NO-GO: **GO** |
| Post-restore | fresh smoke txn CONFIRMED on the restored cluster |

Verbatim:
```
DRILL 15:35:40: D1b seed ok — payments/journal/postings/events = 50000/50000/150000/50000
DRILL 15:35:43: D2 backup ok — dargent-20260908-153540.dump (5635696 bytes), manifest: payments=50003 ΣDR=264980000 ΣCR=264980000
GO/NO-GO: GO — restore verified (counts match manifest, ΣDR=ΣCR=264980000, projection==lines) in 23s
DRILL D4 restore VERIFIED — measured RTO 23s (budget 1800s)
```

**Honest delta, both ends now measured:** 36 KB/3 txns → ~20 s; 2.28 MB/20k txns → 21 s;
5.6 MB/50k txns (200k lines) → **23 s**. RTO stays flat across this class — dominated by
boot + pg_restore fixed overhead. Measured, never extrapolated.
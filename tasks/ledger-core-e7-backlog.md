# Ledger Core E7 — Backlog

**Epic:** E7 — double-entry journal, balance projection, proof, settlement · **Milestone:** M2 (completes with E10)
**Spec:** `ledger-core-e7-spec.md` (contracts binding) · **Sequence:** `ledger-core-e7-implementation-sequence.md`

## Story map

| → Deliver | → Survive | → Govern | → Prove |
|---|---|---|---|
| Journal + postings from `payment.confirmed` (S2) | Dedupe by `event_id`, poison→DLQ (S2/S3) | TD-13 residual fix first (S0) | IT1 full money loop (S5) |
| Balances projection + proof + rebuild (S2/S4) | `fee+net==amount` gate → REJECTED (S2) | Journal never purged, BoE addendum (S6) | IT2 redelivery, IT4 corrupt→rebuild (S5) |
| Settlement with idempotent replay (S4) | `FOR UPDATE` on balance rows (S4) | Env contract §4.1 verbatim (S3) | IT5 settlement, IT6 DLQ (S5) |
| Fan-out queue topology (S3) | Boundaries: no payments imports (all) | Flip last + one post-flip citation commit (S7) | Boundary greps w/ commit id (S7) |

## Items

| ID | Item (spec §) | Acceptance anchor |
|---|---|---|
| S0 | **TD-13 residual (docs-only)**: kill the off-by-one (requestId=BD-5…read side=BD-10), restore real R-structure (R3 read side, single R6, R7 docs truth / R8 governance), fix R0's #15/#17 = `33288538459` → **#20**, correct the TD-10 row (stale-grep defect; #22 = `33289414922`), add TD-12/TD-13 rows with commit ids | e3r matrix ↔ register 1:1; every pair matches the canonical tables |
| S1 | Schema: `V101__journal.sql`, `V102__balances.sql`, `V103__settlements.sql` under `db/migration/ledger/` (§5.2; list the migration dir FIRST — raw-guessing paths is an audited 404 pattern) | Flyway applies on PG16; CHECKs exact |
| S2 | Domain + `EventIngestionUseCase` + `LedgerStore` port, pure TDD (§5.3, §5.4, §5.7) | Unit suite: reader strictness, posting math, dedupe, REJECTED gate |
| S3 | `SqsEventConsumer` + compose/init-script v2 (fan-out queue + DLQ + subscription) + `.env.example` (§4.1, §5.1) | Script re-run no-ops; consumer hosted behind `DARGENT_LEDGER_CONSUMER_ENABLED` |
| S4 | `SettlementUseCase` + rebuild + HTTP surface (§5.5, §5.4, §5.6) | Units: guards, replay, race; routes in SecurityConfig explicitly |
| S5 | ITs 1–6 (§7) on PG16 + LocalStack, static creds, `runOnce()`-driven | Test names + run pairs; IT1 is the headline M2 cell |
| S6 | BoE addendum + README/CHANGELOG truth-scoped entries (M2 ◐ until E10) (§5.8) | Docs claim only what cited runs prove |
| S7 | Matrix + ledger flip (last content commit) + one post-flip citation commit; boundary greps w/ commit id (§9) | DoD fully checked |

## Explicitly out (do not absorb)
E10 consumer · E8 reversals/refunds · partial settlements · archival · multi-currency · any `modules/payments`
prod change · new deps beyond `sqs` (anything else = stop-and-report).

## Debt & adjacencies
- TD-13 residual → S0 of THIS epic (registered in the e3r spec row).
- DEBT-1 (`Payment.restore()`) → belongs to E5's persistence seam, not here.
- DEV-R2-4 formal ratification — still owed by the owner; requested again at E7 close.

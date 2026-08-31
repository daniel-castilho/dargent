# Ledger Core E7 — Implementation Sequence

**Rule of engagement:** the executor commits; **push is the owner's**; every claim audited via API
(message = diff standard). One R per commit. Every push green; pairs (number AND id) cited from the
canonical tables (`e3r-block1-verification.md`, E6 matrix §Run IDs) — TD-13 exists because labels drifted.

## Order

| Step | Commit (conventional) | Content | Done means |
|---|---|---|---|
| S0 | `docs(e3r): TD-13 residual — register ids, R-structure, R0/TD-10 pairs, TD-12/13 rows` | Backlog S0 list, verbatim | Matrix ↔ register 1:1; pairs match; CI green |
| S1 | `feat(ledger): journal, postings, balances, settlements schema` | §5.2 | Flyway green on PG16; CHECKs in place |
| S2 | `feat(ledger): event ingestion use case with dedupe, posting rules, strict reader` | §5.3/5.4/5.7 | Pure unit suite green (no Spring) |
| S3 | `feat(ledger): SQS consumer + fan-out queue topology` | §4/§5.1 | Consumer gated; init script v2 idempotent (re-run proof); `.env.example` rows |
| S4 | `feat(ledger): settlement, rebuild, and ledger read API` | §5.4–5.6 | Units green; routes explicit in SecurityConfig |
| S5 | `test(ledger): ITs — full loop, redelivery, non-posting, proof/rebuild, settlement, DLQ` | §7 | IT1–IT6 green, pairs cited |
| S6 | `docs(e7): BoE addendum + truth-scoped README/CHANGELOG` | §5.8 | Docs claim only cited runs; M2 ◐ until E10 |
| S7 | `docs(e7): matrix + ledger flip` then `docs(e7): record flip run pair` (LAST) | §9 | Matrix zero pending; flip + exactly one post-flip citation commit, green |

Block split (E3R/E6 pattern): **Block 1 = S0–S3** (`ledger-core-e7-execution-prompt-block1.md`),
**Block 2 = S4–S7** (prompt issued on Block 1's verified handoff). Handoffs audited via API.

## Global rules (binding)

1. Evidence = CI test name + run number AND id, pair API-verified against the canonical tables.
2. Commit message = diff: pre-push self-check, every bullet matched to a hunk.
3. Zero new deps beyond `software.amazon.awssdk:sqs`; zero new env names beyond §4.1; zero edits to
   payments migrations; zero `modules/payments` prod changes. Violations = stop-and-report, never improvise
   (BD-14's law).
4. Scope: `modules/ledger`, `apps/api`, `deploy/localstack-init.sh`, compose, `.env.example`, `docs/`,
   `tasks/`. Zero lines in `modules/payments` (prod), `modules/notifications`, `apps/psp-simulator`.
5. Schema↔spec divergences discovered while writing migrations = stop-and-report BEFORE committing.
6. 100% English sources; injected `Clock`; zero `Thread.sleep`.

## Failure playbooks

| Symptom | Play |
|---|---|
| Fan-out not delivering to the ledger queue | Assert subscription attrs first (`GetQueueAttributes`/ListSubscriptions), then publisher; ITs assert the contract, not LocalStack internals |
| Duplicate journal rows | Dedupe INSERT must be the tx's first statement; check ON CONFLICT target = PK `event_id` |
| Signed-balance assertion failures | Convention is credit-positive (§5.2); fix code, never flip signs ad hoc — if the convention itself is wrong, stop-and-report |
| Poison message loops instead of DLQing | The ack policy is binary (§5.3); verify you are NOT acking parse failures and that redrive is on the LEDGER queue |
| Settlement lost update under race | `SELECT … FOR UPDATE` on `ledger.balances` present? Fix the tx, not the test |
| Proof flaky under consumer concurrency | Drive determinism via `runOnce()` + barriers; proof runs quiescent in ITs |
| Matrix cell without a real pair | Cell stays open and says so — TD-13's lesson is S0 of this epic |
| CI red on any push | Artifact, classify in writing, fix |

## Closure gate

E7 closes when DoD §9 is fully checked, the matrix has zero pending cells, and the flip is the last content
commit followed by exactly one green post-flip citation commit (E6's consecrated rule). Then — and only then
— **E10 is commissioned** (closes M2), E5 after (DEBT-1's home).

# Outbox & Messaging Backbone E6 — Implementation Sequence

**Rule of engagement:** the executor commits; **push is the owner's action**; every claim is audited via API
(message ≠ diff checks are standard). One R per commit (S0 may be a single docs commit). Every push green;
run ids cited as number+id pairs verified via API — the canonical pair table lives in
`tasks/e3r-block1-verification.md` and TD-13 exists precisely because labels drifted three handoffs in a row.

## Order (dependency-driven; no step starts before the previous one's push is green)

| Step | Commit (conventional) | Content (spec §) | Done means |
|---|---|---|---|
| S0 | `docs(e3r): TD-13 citation-layer correction — matrix register ids, run-id pairs, artifact index` | Backlog S0 list, verbatim | Matrix matches register 1:1; all pairs match the canonical table; CI green (docs-only) |
| S1 | `feat(payments): UUIDv7 outbox row ids (OutboxId VO)` | §5.5 | Property tests green; writers switched; run pair cited |
| S2 | `feat(payments): outbox delivery use case with claim/backoff/mark policy` | §5.1, §5.2 | Pure unit suite green; no Spring in the test |
| S3 | `feat(api): SNS event publisher + LocalStack compose topology` | §4, §5.2 | Bean wiring + idempotent init script + `.env.example` rows |
| S4 | `test(payments): relay ITs — publish, retry, SKIP LOCKED race` | IT1–IT3, §6 | 3 ITs green on PG16+LocalStack |
| S5 | `feat(payments): outbox retention purge + BoE sizing doc` | §5.4, §5.7 | IT4 green; `docs/load-test-baseline.md` landed |
| S6 | `test(payments): purge, end-to-end delivery, topology ITs` | IT4–IT6 (if not already), §7 | **IT5 M2 anchor green** — event on the queue |
| S7 | `docs(e6): matrix, README/CHANGELOG truth-scoped, ledger flip` (LAST) | §9 | Matrix zero pending; E6 ✅ with final run id at that HEAD; E5 unblocked |

Suggested block split (same pattern as E3R): **Block 1 = S0–S3** (foundations + publishing), **Block 2 = S4–S7**
(proofs + truth). Execution prompts per block are derived from this file; the owner commissions each block.

## Global rules (binding, inherited from AGENTS + E3R adjudications)

1. Evidence = CI test name + run number AND id, the pair API-verified. Labels drifted 3× (TD-11…TD-13) —
   pairs are re-checked against the canonical table before any handoff.
2. Commit message describes exactly its diff (pre-push self-check: every bullet matched to a hunk).
3. Zero migrations in E6; V105 drift → stop-and-report (schema↔spec rule, BD-14's law).
4. New env names only from §4.1; `PSP_*`/`CHAOS_*` untouched; yaml follows the spec.
5. Scope: `modules/payments`, `apps/api`, `deploy/` (init script only), `docs/`, `tasks/`, compose,
   `.env.example`, `README.md`, `CHANGELOG.md`. Zero lines in `modules/ledger`, `modules/notifications`,
   `apps/psp-simulator` — checked with `git diff --stat` before every push.
6. AWS SDK v2 imports confined to `adapter/out/messaging/` (boundary script extended if cheap; ArchUnit rule
   if not — either way, grepped at S7 with the commit id pasted).
7. A disabled/skipped test is registered debt (§5.5); a spec that can't compile is stop-and-report (DEV-R2-4).

## Failure playbooks

| Symptom | Play |
|---|---|
| LocalStack container unhealthy in CI (flaky) | One retry, then red = artifact + written classification; never mask with waits; if 2+ flakes across runs → healthcheck/start-period fix in compose, disclosed in the PR/commit body |
| FIFO publish rejected (name/attr) | `.fifo` suffix required on topic AND queue names; `MessageDeduplicationId` required — check the init script first, then the publisher |
| Byte-equality assert fails on jsonb text | Fix the ASSERTION TARGET (seed with the exact text the adapter reads), never loosen to "semantic" silently — if semantic-only is truly right, change the spec cell first and say so |
| Two-thread race IT flaky | Barriers + `runOnce()` determinism (house pattern); no sleeps; if SKIP LOCKED genuinely interleaves, the assert counts outcomes, not threads |
| SNS SDK retries amplify a red test | Timeout override present? Fix config, not the test's expectations |
| Matrix cell can't cite a real pair | Cell stays open and says so — never invent (the TD-13 lesson is the epic's own Step 0) |
| CI red on any push | Artifact, classify in writing, fix; never push on an unexplained red |

## Closure gate

E6 closes only when: DoD §9 fully checked, matrix zero pending, **ledger flip is the last commit of the epic
with its own green run id cited at that HEAD** (the E3R rule that finally worked), hygiene greps pasted with
their commit id. Then — and only then — E5 (expiration/reconciliation) is commissioned; E3.5 and the V107
typo stay queued behind it.

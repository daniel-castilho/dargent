# AI Software Engineer Prompt — Epic E7: Ledger Core

**Issued:** 2026-08-31 · **Executor:** the AI Software Engineer · **Milestone:** M2 (completes with E10)
**Mission:** give the confirmed money events a financial home — immutable double-entry journal, balance
projection with a proof, rebuild, settlement — and first pay the documentation debt you inherited (TD-13
residual). Authorship note: this spec was authored by the governance side, not by you; where you disagree,
**ask before diverging** (the anti-BD-14 rule that earned you the E6 clarifications round).

---

## Where you are starting from (verified facts — cite pairs, never memory)

- main = `e6d8751` (post-flip citation commit; parent = E6 flip `084bb3a`). Runs: #47 (`33355346290`) flip,
  #48 (`33355665328`) green on `e6d8751`. E6 closed: relay, retention, `payment.confirmed` reaching
  `dargent-payments-notify.fifo` (IT5) — with `MessageGroupId=txid`, `MessageDeduplicationId=eventId`.
- **TD-13 residual is open** (e3r spec row): e3r matrix still carries the off-by-one ids, the rejected
  R-structure, R0/TD-10 mispairs, missing TD-12/13 rows. Your FIRST commit fixes exactly that list.
- `modules/ledger` is an empty E0 skeleton; **JDBC (`JdbcClient`) only — no JPA/entities** (stack law).
  List the migration directory before assuming its shape (audited 404 pattern).
- E6 rules you inherit: static test creds for LocalStack ITs; init script idempotency; relay untouched;
  post-flip citation commit pattern.

## Sources of truth — binding, in this order

1. `tasks/ledger-core-e7-spec.md` — §4.1 env contract, §5 exact contracts (posting rules §5.3 are the money
   math), §6 races, §7 tests, §9 DoD.
2. `tasks/ledger-core-e7-implementation-sequence.md` — S0…S7, global rules, failure playbooks.
3. `tasks/ledger-core-e7-backlog.md` — acceptance anchors.
4. `tasks/create-webhook-remediation-e3r-spec.md` §2 + `tasks/e3r-block1-verification.md` + E6 matrix —
   governance + canonical run pair tables (needed by S0).

## Build order (one commit per step; every push green; pairs cited)

1. **S0 `docs(e3r): TD-13 residual`** — the registered list, docs-only, no code, no flips.
2. **S1 `feat(ledger): journal, postings, balances, settlements schema`** — three migrations (§5.2);
   CHECKs exact; expand-only.
3. **S2 `feat(ledger): event ingestion use case with dedupe, posting rules, strict reader`** — pure TDD
   behind `LedgerStore` port; the ack policy (§5.3) and guarantee statement (§5.7) in javadoc.
4. **S3 `feat(ledger): SQS consumer + fan-out queue topology`** — second queue + DLQ + subscription via the
   init script (re-run no-op proof), `.env.example` §4.1, consumer hosted in `apps/api` behind
   `DARGENT_LEDGER_CONSUMER_ENABLED`, `runOnce()` pattern.
5. **S4 `feat(ledger): settlement, rebuild, and ledger read API`** — §5.4–5.6; routes explicit in
   SecurityConfig; settlement idempotent replay per E3 semantics.
6. **S5 `test(ledger): ITs`** — IT1–IT6 (§7); IT1 is the M2 headline cell; static creds; zero sleeps.
7. **S6 `docs(e7): BoE addendum + truth-scoped README/CHANGELOG`** — M2 stays ◐ until E10.
8. **S7 `docs(e7): matrix + ledger flip`** — then the single post-flip citation commit recording the flip's
   run pair; that commit is the epic's final commit and must be green.

Block commissioning: **Block 1 = S0–S3** (prompt: `ledger-core-e7-execution-prompt-block1.md`), **Block 2 =
S4–S7** (issued on Block 1's verified handoff). Both handoffs audited via API: messages vs diffs, sources
read line-level, pairs re-verified against run objects.

## Non-negotiable rules

1. At-least-once + local dedupe by `event_id`; journal exactly-once-per-event by construction; nobody
   writes "exactly once" (§5.7 verbatim).
2. Zero new deps beyond `software.amazon.awssdk:sqs`; zero new env names beyond §4.1; zero payments prod
   changes; zero payments-migration edits. Anything outside = stop-and-report.
3. Posting math changes (§5.3) are spec changes — ask first, never adjust silently to make a test green.
4. Commit message = diff; pairs re-verified before handoff (TD-13's lesson is your own S0).
5. Scope: `modules/ledger`, `apps/api`, `deploy/localstack-init.sh`, compose, `.env.example`, `docs/`,
   `tasks/` — `git diff --stat` checked before every push.

## Stop conditions

| When | Do |
|---|---|
| Posting rules/amounts don't add up (spec or event) | REJECTED path (§5.3) in code; spec ambiguity → stop-and-report |
| Schema or migration folder differs from assumptions | Stop-and-report after listing, before writing |
| A needed dep/env is outside §4/authorized list | Stop-and-report |
| CI red on any push | Artifact, classify in writing, fix |
| A matrix cell lacks a real pair | Cell stays open and says so |

## Handoff report (will be API-audited)

- S0: before/after of the e3r matrix rows + pairs; greps with commit id.
- S1–S3: commit shas + messages + test names + run pairs; migration file list (from the tree, not memory);
  init-script idempotency proof; `.env.example` diff.
- Explicit list of anything you did NOT do from the prompt, with reason; any clarification you needed, asked
  BEFORE diverging.

Then stop. **Block 2 (S4–S7: settlement/rebuild/HTTP, ITs 1–6, BoE, matrix + flip) is commissioned on
verified evidence of this handoff.**

# Epic Prompt — E5: Expiration, Resurrection & Reconciliation (opens Milestone M3)

**Issued:** 2026-09-02 · **Owner decision:** package commissioned this channel ("prepare the complete package").
**Executor:** the AI Software Engineer · **Auditor:** the governance side (API-audited: messages vs diffs,
run pairs number AND id, reds always cited, greps at the cited commit). **Push is the owner's action.**

## What E5 is — the soul of the project

Today a payment whose webhook never arrives stays PENDING forever, and a payment nobody confirms past
`expires_at` never expires. E5 closes the money loop:

- an **expiration scheduler**: due PENDING payments → `EXPIRED` (conditional UPDATE) + `payment.expired`
  outbox row + audit;
- a **reconciler**: scheduled poller of the PSP's truth endpoint (`GET /cobs/{txid}`) that confirms on its
  own — `PENDING→CONFIRMED` (`late=false`) or **resurrects** `EXPIRED→CONFIRMED` (`late=true`), exactly
  once, with audit — proving scenarios 9–11, 26–27;
- a **journal coverage auditor** (DEBT-4): every CONFIRMED payment must have a ledger journal; a dangling
  payment (no journal) or dangling journal (no payment) is an **incident**, surfaced by log + audit row.

E5 changes payments and the composition root only. The ledger consumes `payment.expired` as it consumes
any unknown type (IGNORED — already proven); notifications record it (proven). **No M3 flip at E5 close**
(M3 = E5+E8+E9); only the E5 row flips.

## Sources of truth — binding, in this order

1. `docs/epics.md` — E5 brief ("expiration scheduler (partial index `WHERE status='PENDING'`, conditional
   UPDATE — design.md §5.1); late confirmation resurrects with `late=true` + audit; reconciler polls
   `GET /cob` and confirms on its own. Proves scenarios 9–11, 26–27").
2. `tasks/expiration-reconciliation-e5-design-seed.md` — **pre-adjudicated decisions** (2026-08-29):
   polling not DLQ-driven; 60 s fixed scan + per-payment backoff 1 m→5 m→15 m (RPO anchor)→1 h cap;
   `V111` adds `next_reconcile_at`; scheduler has NO `@Transactional` on the scheduled method (per-payment
   tx); no ShedLock; audit command names; `late=true` rides the envelope payload.
3. `tasks/expiration-reconciliation-e5-spec.md` — exact contracts (§4.1 env, DDL, flows, IT names).
4. `AGENTS.md` §3.2 (conditional UPDATEs arbitrate races), §3.3/§3.4, §4.1 (env = contract), §8 (DEBT-1
   row), §9d (divergence = stop-and-report BEFORE diverging).
5. `docs/testing-playbook.md` §4 — scenarios 5? no: **9, 10, 11, 26, 27** + injected-Clock and
   no-sleep discipline. `docs/design.md` §5.1.

**Standing rule: if docs and config diverge, STOP — do not reconcile silently, do not pick a side.**

## Debts that come home here (registered; owner-ratified via this commissioning)

- **DEBT-1** — `Payment.restore()` trusts snapshots without revalidating: rejecting-contract test
  (corrupt snapshot → exception) rides Block 1 step 0 (test-only).
- **DEBT-4** — nothing detects CONFIRMED-without-journal: the journal coverage auditor is a Block 2
  story (composition-root glue; module isolation untouched; flag+audit, never auto-repair).

## Non-negotiables

- **Env names are a contract.** New names ONLY via spec §4.1 (`DARGENT_EXPIRATION_*`,
  `DARGENT_RECONCILER_*`, `DARGENT_JOURNAL_COVERAGE_*`). Existing names never change.
- Every transition is a **conditional UPDATE** (`WHERE status = '...'`) — the database arbitrates
  races (blue-green double-scheduler safe by design; no ShedLock, no advisory lock in E5).
- **Injected `Clock` everywhere** — time-travel tests, zero `Thread.sleep`; Awaitility for broker-bound
  outcomes only. Schedulers run in `apps/api` (module main stays Spring-free); ITs drive them via
  `runOnce()`-style methods — CI runs the scenarios (disabled tests = debt).
- E5 **only writes rows** (payments + outbox + audit). Delivery/backoff/republish is E9; outbox
  mechanics are E6's. Do not reuse E9's delivery numbers for reconciler polls.
- TDD on pure domain; real Postgres + WireMock (PSP) at the seams; commit message = diff; pom additions
  disclosed; zero touches in `modules/ledger`, `modules/notifications`, `apps/psp-simulator`.

## Blocks

- **Block 1** — step 0 rider (DEBT-1), V111, expiration scheduler, reconciler engine, core ITs
  (scenarios 26 + 11). Prompt: `tasks/expiration-reconciliation-e5-execution-prompt-block1.md`.
- **Block 2** — give-up window, scenarios 9/10 legs, journal coverage auditor, docs honesty pass
  (the README's "future reconciliation job (E5, not started)" becomes TRUE present tense), matrix,
  E5 row flip + citation. Prompt issued after Block 1's audit.

## Handoff (API-audited, per block)

Commit shas + messages; test names + run pairs (number AND id); grep outputs with commit ids;
anything NOT done with reason; clarifications asked BEFORE diverging. Then stop.

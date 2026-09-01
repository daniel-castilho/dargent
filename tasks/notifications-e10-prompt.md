# Epic Prompt — E10: Notifications Consumer (closes Milestone M2)

**Issued:** 2026-09-01 · **Owner decision:** commission approved this channel (riders included).
**Executor:** the AI Software Engineer · **Auditor:** the governance side (every claim API-audited:
messages vs diffs, run pairs re-verified by number AND id, greps re-read at the cited commit).
**Push is the owner's action.** The engineer commits locally; never pushes.

---

## What E10 is

The payments module already publishes every lifecycle event to an SNS topic; E6's topology fans it
out to **two** FIFO queues. E7 consumed the ledger queue. The second queue — the **notify queue** —
has been receiving events with no consumer since E6. E10 gives it one:

- a **notifications module** (`modules/notifications`, package `io.dargent.notifications`) that
  consumes the notify queue and persists one **durable notification record per event**, idempotent
  by `event_id`;
- a minimal **read API** (`GET /v1/notifications`, merchant-scoped, cursor pagination, API-key
  auth reused from payments) so the records are demonstrable end-to-end;
- poison handling identical to the ledger: binary ack, unparseable body → nack → notify DLQ
  (queue already exists from E6).

**Scope boundary:** E10 records and serves notifications. It does **not** send email/push/webhook.
Delivery channels are future scope and must not be sketched, stubbed, or promised in code or docs.

## Sources of truth — binding, in this order

1. `docs/epics.md` — the canonical epic ledger (E10 row) and milestone map (M2 closes here).
2. `modules/ledger/**` — the reference implementation. The notifications module **mirrors** the
   ledger's structure, consumer semantics, reader contract (post-BD-16 shape), store port pattern,
   test harness, and hygiene gates. When in doubt, copy the ledger's shape — "1000 maneiras" rule:
   read, understand, reimplement in the house style.
3. `tasks/notifications-e10-spec.md` — exact contracts (env §4.1, DDL, API, IT names). Deviations
   are stop-and-report, never silent.
4. `AGENTS.md` §2 (module isolation), §3.3/§3.4 (idempotency, event semantics), §9d (divergence =
   stop and report BEFORE diverging — the BD-15R lesson).
5. `docs/coding-standards.md` §10 + TD-15/TD-8 precedent — documentation honesty rules.

**Standing rule: if docs and config diverge, STOP — do not reconcile silently, do not pick a side.**

## Non-negotiables

- **Env names are a contract.** New names only via spec §4.1 (`DARGENT_NOTIFS_*`). Existing names
  (`CHAOS_*`, `PSP_*`, `DARGENT_LEDGER_*`, AWS_REGION/endpoint) are never renamed.
- Stack: Java 25, Maven, Boot 4.x, JDBC via `JdbcClient` (no JPA/entities), Postgres 16, Flyway
  per-module, Jackson 3 (`tools.jackson.*`) only, SQS SDK confined to `adapter/out/messaging/`.
- TDD on pure domain; WireMock/Testcontainers at the seams; zero skips, zero `@Disabled`,
  zero `Thread.sleep` in tests.
- **Jackson 2 is contraband**: `grep -rn "com.fasterxml" modules/notifications/src/main` = 0 hits
  (BD-16's grep list grows into this module from day one).
- Zero lines in `modules/payments`, `modules/ledger`, `apps/psp-simulator` prod sources (riders in
  step 0 are the only sanctioned exceptions, and they are listed in the block-1 prompt).
- Any new Maven dependency (e.g. `software.amazon.awssdk:sqs` for this module) is **disclosed in
  the commit message** that adds it.
- Commit message = diff. Never reference an artifact that does not exist yet. Every confirmed
  claim cites run pairs (number AND id) verified via API — local prints are not evidence.

## Blocks

- **Block 1** — step 0 riders (TD-15 + BD-15R + matrix nit), then module scaffold, consumer, ITs.
  Prompt: `tasks/notifications-e10-execution-prompt-block1.md`.
- **Block 2** — read API, docs (README/CHANGELOG/epics ledger), flip + M2 closure. Prompt issued
  after Block 1's audit.

## Handoff (API-audited, per block)

Commit shas + messages; test names with run pairs (number AND id); grep outputs pasted with the
commit id they ran at; anything NOT done, with reason; clarifications asked BEFORE diverging.

Then stop. The auditor verifies; the register and the milestone flip only on verified evidence.

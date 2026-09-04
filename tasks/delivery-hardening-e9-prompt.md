# Epic Prompt — E9: Delivery Hardening (closes Milestone M3)

**Issued:** 2026-09-03 · **Owner decision:** package commissioned this channel ("Prepara o E9").
**Executor:** the AI Software Engineer · **Auditor:** the governance side (API-audited: messages vs
diffs, run pairs number AND id, reds always cited). **Push is the owner's action.**

## What E9 is — the last delivery mile, mostly FINISHING existing work

E6 built the bus and a relay that already retries (30s→2m→5m backoff as-built in
`OutboxDeliveryUseCase.backoff()`), but retries are **unbounded** and the schema's `EXHAUSTED`
state is **unreachable dead code** (`Policy.maxAttempts` "unbounded in E6 — E9 owns EXHAUSTED").
E9 finishes the state machine and adds the operational tools:

- **Exhaustion contract**: after the 3rd failed attempt (E6's ladder, never touched), the outbox row
  goes `PENDING → EXHAUSTED` and STOPS scheduling — a bounded-failure guarantee, not infinite retry;
- **Audited requeue endpoint**: `POST /v1/outbox/{id}/requeue` — EXHAUSTED → PENDING (attempts
  reset), audit `outbox_requeued` (actor = API key), admin-gated;
- **Republish tool**: replay a time window of already-SENT rows (new rows, fresh event_ids) —
  powers recovery AND scenario 20's no-double-journaling proof (consumers dedupe by their own
  event_ids);
- **DLQ inspection recipes**: documented, query-backed (no new endpoint) — count/peek/redrive-runbook
  for both consumer DLQs;
- **S6's never-negative arbitration joins the family** (the drain is already the exemplar).

**M3 flips here.** This is the last M3 epic; the flip carries E5+E8+E9 — the "Suffering" milestone
becomes a delivered row.

## Sources of truth — binding, in this order

1. `docs/epics.md` — E9 brief: "Backoff 30s→2min→5min, `FAILED`→`EXHAUSTED`, audited requeue
   endpoint, outbox republish tool (our replay), DLQ inspection recipes. Proves scenarios 18–20."
2. `docs/testing-playbook.md` §4 — scenario 19 (backoff → FAILED → EXHAUSTED → audited requeue →
   SENT), scenario 20 (republish replays a period without double-journaling), scenario 18 (poison
   → DLQ, already proven — E9 documents, not re-proves).
3. `tasks/delivery-hardening-e9-spec.md` — exact contracts (§4.1 env, requeue semantics, republish
   rules, IT names). **Env names ONLY via spec §4.1.**
4. `AGENTS.md` §3.2/§3.3/§3.4/§4.1/§8/§9d · design §7.2/§7.3 (routing + topology).

**Standing rule: docs vs config diverge → STOP.** Divergences → stop-and-report BEFORE diverging.

## Non-negotiables

- **Env names**: only §4.1 (`DARGENT_RELAY_MAX_ATTEMPTS` joins the existing `DARGENT_RELAY_*`
  family — defaults are contract; existing names never change).
- **E6's ladder is frozen**: 30s/2m/5m backoff and its semantics are NEVER retuned in E9 (the
  numbers live in code with a spec note; changing them is an owner decision).
- **EXHAUSTED is reached only by attempts** — exhaustion is earned by the counter, never set by
  admin action; requeue resets the counter honestly (a requeued row can re-exhaust).
- **Republish creates NEW rows** with NEW event_ids (`eventId = deterministic function of original
  eventId + `#replay`-salt or fresh UUID — spec §5 decides, ONE way) — republish never mutates a
  SENT row, never reuses an event_id (consumers' dedupe is the truth; scenario 20 proves it).
- **Requeue is audited and idempotent** (conditional UPDATE `WHERE status='EXHAUSTED'`; double
  requeue = one effect + audit). Admin auth: separate admin key scope — if the auth model doesn't
  have one, stop-and-report the minimum viable guard (do not invent a roles system).
- No new tables (outbox V105 already has every needed column); migrations only if §2 of the spec
  truly requires (stop-and-report otherwise — target: zero migrations).
- TDD; conditional UPDATEs; injected Clock; zero sleeps/disabled; module mains Spring-free;
  commit message = diff; **zero lines in `modules/ledger`, `modules/notifications`,
  `apps/psp-simulator` prod sources**.
- **Docs honesty riders at S4** (the maturity-conversation standing rule): README/CHANGELOG claims
  about delivery guarantees become present tense only WITH proof; grep-before-claim on any
  as-built statement.

## Blocks

- **Block 1** — exhaustion contract + requeue endpoint (scenarios 19's spine), republish tool +
  no-double-journaling proof (scenario 20). Prompt:
  `tasks/delivery-hardening-e9-execution-prompt-block1.md`.
- **Block 2** — DLQ inspection recipes (docs + query proofs), docs honesty pass, matrix, **E9 row
  flip + M3 flip + citation**. Prompt issued after Block 1's audit.

## Handoff (API-audited, per block)

Commit shas + messages; test names + run pairs (number AND id) including reds; greps with commit
ids; anything NOT done with reason; clarifications asked BEFORE diverging. Then stop.

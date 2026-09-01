# E10 Sequence — Notifications Consumer

Execution order, global rules, and failure playbooks. The backlog says WHAT; this file says in
which order and what to do when reality pushes back.

## Execution order (strict)

```
Block 1
  step 0: riders, one commit each, each pushed green before the next
    0a. docs: README honesty pass (TD-15)                     [README only]
    0b. test(ledger): failure-injection leg in BD-15 guard IT [LedgerMoneyLoopIT only]
    0c. docs(e7): complete race-row citation pair in matrix   [e7 matrix only]
  step 1: S1 scaffold + migration (module compiles, migration applies in IT harness)
  step 2: S2 reader (TDD: unit tests first, then implementation)
  step 3: S3 use case + port + adapter (TDD on fakes)
  step 4: S4 consumer + wiring (unit translation contract first)
  step 5: S5 ITs (loop, poison-DLQ) — the block's proof
  step 6: block-1 handoff (pairs, greps, divergences)
Block 2 (prompt issued after Block 1 audit)
  step 6→7: S6 read API (contract tests first) → S7 docs + flip + M2 closure
```

Each numbered step = at least one commit; every push green; test+run pairs cited (number AND id).

## Global rules (house law, restated for this epic)

1. **TDD**: failing test first on pure domain; no production line without a failing test that
   demands it (adapter seams covered by ITs, not by mocks-of-the-DB).
2. **WireMock/Testcontainers at the seams**; LocalStack for SQS; real Postgres via Testcontainers.
   The sandbox has no docker — CI is the executor for ITs; unit runs may be local.
3. **Binary ack** (E7 contract): `processMessage == true` → delete; anything else → leave visible.
   Never partial-batch-delete on failure.
4. **Idempotency by schema**, not by memory: UNIQUE(event_id) + `ON CONFLICT DO NOTHING`.
5. **Per-module Flyway**, forward-only, expand-only. Never edit a landed migration.
6. **Module isolation**: notifications main has zero Spring annotations, zero cross-module prod
   imports (shared `io.dargent.shared.events.EventEnvelope` allowed — ledger precedent), AWS SDK
   only inside `adapter/out/messaging/`.
7. **Jackson 3 only**; BD-16's reader shape is the template; the `com.fasterxml` grep is a
   permanent hygiene gate for this module from its first commit.
8. **Commit message = diff**; pom additions disclosed in the message that adds them; no artifact
   referenced before it exists.
9. **Env contract**: names born in spec §4.1; defaults are part of the contract
   (`DARGENT_NOTIFS_CONSUMER_ENABLED` default **false** — consumers are opt-in like the ledger's).
10. **Docs honesty** (§10): README/CHANGELOG describe the present. Notifications enter the
    present tense only at S7, after the proof lands.

## Failure playbooks

### P1 — CI red on a push
Do not stack the next commit. Reproduce or explain the failure in writing (test name, assertion,
diff suspicion). Fix-forward with a new commit; cite the red run id and the green run id in the
handoff. Never rebase-push, never force-push, never rewrite history.

### P2 — Felt need to diverge from spec (env name, DDL, API shape, test name)
STOP before writing the diverging code. Ask the auditor with the concrete alternative. The
BD-15R precedent: an honest post-hoc disclosure avoided a false citation but still opened a
residual — pre-divergence consultation is cheaper.

### P3 — A test can only pass by weakening an assert
STOP. That is a design smell or a harness gap, never a test edit. Report the exact assert and
the pressure it faces.

### P4 — LocalStack/Testcontainer flake
One rerun allowed, both runs cited. A second flake of the same test = STOP with the log excerpt;
the harness gets hardened before the epic proceeds.

### P5 — Docs vs config divergence (standing rule)
Any mismatch between README/epics.md/AGENTS.md and what the code or infra actually does: STOP,
report the exact lines. Silence is how TD-8 survived four epics.

### P6 — Scope creep pressure (delivery channels, templates, preferences)
Out of scope by charter. If a reviewer or analysis demands them, the demand goes to the owner;
the engineer does not pre-implement "while we're here".

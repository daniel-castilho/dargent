# Execution Prompt — E3R Block 1: the Create Path (R1 → R4)

**Issued:** 2026-08-30 · **Executor:** the AI Software Engineer owning `modules/payments` + `apps/api` for this block
**Valid for:** exactly the stories R1, R2, R3, R4 of `tasks/create-webhook-remediation-e3r-backlog.md`. Nothing else.
**Operating principle:** a green CI proves that tests pass — not that they are right, and not that the code
exists. Your job is to make the claims true and let the CI say so.

---

## Where you are starting from (verified facts — cite run ids, never memory)

- `main` is at `e415de0`; CI run #17 (`33282406570`) is green; the 2 skipped tests are the disabled IT.
- The documents you must obey now live in the repo (`d5215bde`, `1c931f46`): the ledger reads E3/E4
  `◐ reopened (E3R)`, the E4 acceptance matrix carries a VOID banner, the CHANGELOG carries retractions.
- The history you must not repeat is on record: commits `97882494` and `765c4cc` claimed in their messages
  work their diffs did not carry, and a closure matrix cited test classes that never existed. That is the
  failure pattern this block's discipline exists to preclude — including by you, including by accident.

## Sources of truth — binding, in this order

1. `tasks/create-webhook-remediation-e3r-spec.md` — **§2 defect register is your work contract** (BD-1…BD-10,
   MS-1/MS-2, TD-1/TD-2 are yours; TD-4…TD-6 are already closed by docs commits — verify, don't redo)
2. `tasks/create-payment-e3-spec.md` — §5.1 (endpoint), §5.7 (PSP phase), §5.8 (transactional core) are the
   binding behavior contract; §5.1.3 idempotency table and §5.5 BR Code golden vector bind literally
3. `tasks/create-webhook-remediation-e3r-implementation-sequence.md` (Steps 1–4) and the backlog R1–R4
4. `AGENTS.md` §2/§3/§4/§5 and `docs/lessons.md` #12, #13

Story labels are exact and are not yours to re-map: **R2 owns BD-1…BD-9** (use case + PSP adapter);
**R3 owns MS-1, MS-2, BD-10** (endpoint + wiring + read side). The idempotency PK
(`merchant_id, idempotency_key, endpoint`) is migration V104 and **already exists in the database** — no
migration is yours in this block. If you catch yourself redefining what R3 or R5 mean, stop: you are drifting
into the exact paraphrase error this project has already paid for.

## Your mission

1. **R1** — re-enable `CreatePaymentScenarioIT` and let it fail on `main`: the debt made visible, red run id
   recorded as evidence, every failure mapped to its register IDs.
2. **R2** — fix `CreatePaymentUseCase` and `SimulatorChargeAdapter` so the IT goes green scenario by scenario:
   transactional core per §5.8, canonical `PENDING`, PSP truth persisted via conditional UPDATE on the re-read
   aggregate, D19 retry + 409 read-back, real snapshot, real `requestId`, `actor_key_id` = the authenticated
   key's id, envelope via the shared Jackson-3 serializer, callback from `PSP_CALLBACK_URL`.
3. **R3** — land `POST /v1/payments` per spec §5.1 verbatim, wire the bean, fix the read side (BD-10): PIX
   profile from config, injected `Clock`, cursor decoded once and the decoded keyset passed to `findPage`.
   This commit closes the red window — `main` returns to green here.
4. **R4** — delete the debug tests under `adapter/out/psp/` (inventory them first; they test nothing contractual).

## Non-negotiable rules — each has a prior violation on record

1. **The test encodes the spec.** You fix code until the disabled IT's expectations pass. If an expectation
   truly conflicts with the E3 spec, STOP and report the exact conflict — the spec changes in the open, never
   the test in silence. Re-disabling it is the defect this epic exists to remove.
2. **A commit message describes exactly its diff.** Never announce work the diff does not carry; follow-ups
   become backlog items, not promises in messages. Before every push, re-read your message against `git diff`.
3. **Evidence is a CI run id.** "Green locally" is a hypothesis, not a result. A story is done when its run is
   green on `main` — with the single, bounded, deliberate exception of R1's designed red, which is evidence.
4. **You do not author closure.** No ledger edits, no matrix writes, no README/CHANGELOG changes, no epic flips.
   Your output is code + tests + run ids reported back; R7/R8 own documentation truth.
5. **No new dependencies, no new migrations, no env renames.** Env names are contract
   (`PSP_CALLBACK_URL`, `PSP_CREATE_MAX_ATTEMPTS`, `PSP_CREATE_BACKOFF_BASE_MS`, `DARGENT_PIX_KEY`,
   `DARGENT_RECEIVER_NAME`, `DARGENT_RECEIVER_CITY`): read them, never inline their defaults.
6. **Concurrency is arbitrated by the database.** Every post-PSP write re-reads the aggregate and passes the
   row's current version to `updateIfVersionMatches` — never the stale instance, never a literal (BD-3).
   Zero `Thread.sleep` in tests; the backoff sleeper is injected and recorded.
7. **Scope is checked before every push:** `git diff --stat main -- apps/psp-simulator modules/ledger
   modules/notifications` = 0 lines, `bash scripts/check-boundaries.sh` green, no `com.fasterxml.jackson`
   imports in prod sources, no `*.disabled`/`*Debug*` files under test trees after R4.
8. **Jackson 3** (`tools.jackson.*`) everywhere; outbox payloads serialize once through the shared serializer —
   `String.format` JSON is register defect BD-8, not a style choice. No `@WebMvcTest` — full-context MockMvc
   is the house pattern.

## Execution contract (push cadence and commit shapes)

- **R1, alone:** `git mv modules/payments/src/test/java/io/dargent/payments/it/CreatePaymentScenarioIT.java.disabled
  CreatePaymentScenarioIT.java` — zero content edits. Commit:
  `test(payments): enable CreatePaymentScenarioIT as the failing specification (E3R R1)`. Push by itself.
  Record the red run id and the failure → register-ID map as DEV notes in the implementation sequence file.
- **R2:** unit tests first with fakes (one named test per BD id), then the code fixes in small coherent
  commits — e.g. `fix(payments): transactional core and PSP-truth persistence (BD-1..BD-4)`,
  `fix(payments): D19 retry, read-back and honest envelope fields (BD-4..BD-9)`. The running IT goes green
  scenario by scenario; code changes only.
- **R3:** endpoint + wiring + read-side fixes with full-context tests (201 byte-shape including the golden
  BR Code, 401, 400 field maps, cross-tenant 404, cursor walk). Commit: `feat(api): POST /v1/payments (E3R R3)`.
  The red window closes here; cite the green run id.
- **R4:** `git rm` the inventoried debug classes. Commit: `test(payments): remove debug tests (E3R R4 / TD-2)`.

## Stop conditions — halt and report; do not improvise

| When | Do |
|---|---|
| An IT expectation conflicts with the E3 spec | Stop; report the exact conflict for an open spec decision |
| A fix seems to need a migration, dependency, or new env name | Stop; not authorized in this block |
| CI goes red unexpectedly | Pull the artifact, classify in writing, then fix. Never push again on an unexplained red |
| You are about to touch `docs/`, matrices, README, CHANGELOG, or the ledger | Not yours in this block; stop |
| Anything pulls toward webhook intake (validator, intake use case, `WebhookController`) | That is R5/R6 — separately commissioned; stop |

## Handoff report — what you return when the block closes

- Run ids: R1's red id; R2, R3, R4's green ids (API-verified, not local prints).
- Register closure table: BD-1…BD-10, MS-1, MS-2, TD-1, TD-2 → the exact test name proving each.
- Deviations (DEV-…) with rationale, appended to the sequence file.
- Proof lines: hygiene greps zero; scope diff zero; the README curl answers `201` on a local compose stack.

Then stop. You will receive Block 2 (R5–R6, webhook intake) when Block 1's evidence is verified.

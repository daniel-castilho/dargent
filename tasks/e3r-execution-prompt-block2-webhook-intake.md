# Execution Prompt — E3R Block 2: Webhook Intake (R5 → R6) + Golden-Assertions Audit

**Issued:** 2026-08-30 · **Executor:** the AI Software Engineer owning `modules/payments` + `apps/api` for this block
**Valid for:** exactly the stories R5, R6 of `tasks/create-webhook-remediation-e3r-backlog.md` **plus the audit
task A0 defined below** — A0 is a condition of Block 1's acceptance, not optional polish. Nothing else.
**Operating principle:** a green CI proves that tests pass — not that they are right, and not that the code
exists. The webhook is the project's first adversarial surface: assume the caller lies until the bytes prove otherwise.

---

## Where you are starting from (verified facts — cite run ids, never memory)

- `main` is at `a678184`; CI run #19 (`33285295818`) is green; the red window R1→R3 is closed (designed red on
  record: run #18 `33282800600`).
- The create path is real: `POST /v1/payments` wired end to end, proven by `CreatePaymentIT` over real HTTP,
  real DB and real security/PSP adapters (RANDOM_PORT pattern — now the house pattern, DEV-R3-1).
- The E4 building blocks exist and **stand** (R0 verdict): `V108__webhook_events.sql`, `WebhookEventStore` port +
  JDBC adapter. The single `ErrorResponseWriter`, the security filter chain and the dev API-key provisioning are
  live — reuse them; do not rebuild.
- Block 1 carried one adjudicated deviation (DEV-R2-4: the original scenario IT was deleted and replaced). Your
  **A0 audit** below is what makes that acceptance safe. The governance rule it produced binds you: a broken or
  conflicting test is **stop-and-report**, never an in-block replacement decision.

## Sources of truth — binding, in this order

1. `tasks/webhook-intake-e4-spec.md` — **§5.1 (pipeline order is binding), §5.2 (validator + vectors), §5.3
   (processing transaction, order fixed), §5.4 (V108 shape)**. This spec is the contract; its backlog/sequence/
   prompt are superseded by E3R.
2. `tasks/create-webhook-remediation-e3r-spec.md` — §2 register (MS-3 is yours to close) and §5.5 (vectors).
3. `tasks/create-webhook-remediation-e3r-backlog.md` (R5, R6) + implementation sequence (Steps 5–6).
4. `AGENTS.md` §2/§3.2/§3.3/§3.6/§4.4 · `docs/lessons.md` #12/#13 · E1's `confirm()`/`FeeBreakdown`/`BpsRate`
   signatures (consume as-is).
5. Block 1's code as the pattern source: `CreatePaymentIT` (real-server IT style), `ErrorResponseWriter`,
   `PaymentsCompositionConfig`, `JdbcIdempotencyStore` (jsonb handling patterns).

## Task A0 — Golden-assertions audit (first; small; separate commit)

Verify `CreatePaymentIT` (+ the kept `SimulatorChargeAdapterWireMockIT`) still assert the spec's hardest
guarantees; **restore what is missing** in this same commit:

- [ ] 201 body's `brcode` is the **byte-exact golden vector** (174 chars, CRC `EDD2`) — not "a brcode"
- [ ] replay response is **byte-equal** to the original 201 body + `Idempotent-Replay: true`
- [ ] PSP exhaustion records **exactly 3** WireMock requests and asserts backoff **values from the recorded
      sleeper** (never wall-clock)
- [ ] exhaustion **deletes the idempotency key row** (audit_log keeps the trail; a retry with the same key
      starts a fresh payment)
- [ ] cross-tenant read is a **404 from the query**, never 403
- [ ] **scenario 15 restored as a real concurrency proof**: 4 threads + barrier, same `Idempotency-Key`/body →
      exactly one `201`, others `425`, exactly one payment row (it is in no IT today — the replaced suite dropped it)
- [ ] **DB-state asserts, not just JSON**: exhaustion/PSP-truth paths read `status`/`expires_at`/`failure_reason`
      back **from the database** (the stale-aggregate class of defect hides from response-body asserts)

Commit: `test(payments): restore golden spec assertions to create-path ITs (E3R A0)`. Push; green run id required.
If an anchor cannot be restored without changing create-path **behavior**, stop and report — that is a new
register defect, not your call to fix silently.

## Your mission

1. **R5 — `WebhookSignatureValidator` (pure domain, TDD):** verdict order parses → ±300 s window →
   HMAC-SHA256(secret, UTF-8(`ts + "." + rawBody`)), lowercase hex, constant-time compare; verdicts
   `VALID/EXPIRED/INVALID`; injected `Clock`; no Spring, no Jackson. Both vectors byte-exact:
   `sign("1787932800", <E2 §5.4 body>) = 549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113` and
   `sign("1","{}") = e3f75e30c05fa6ab20d1cdd115d4172f6adba335dca3ed37842195aa05305529`; byte-sensitivity
   (wrong key, flipped body byte, `1.0` vs `10`, non-canonical order). Tests first — watch them fail.
2. **R6 — intake use case + controller + ITs:** `WebhookIntakeUseCase` (one transaction, §5.3 order fixed:
   insert `RECEIVED` → parse from `payload_raw` → sanity → conditional confirm (fee 100 bps) →
   `payment.confirmed` outbox row `{amount, fee, net, late:false}` → audit `confirm_from_webhook` →
   `PROCESSED`) + `WebhookController` (raw bytes captured ONCE; `X-PSP-Timestamp`/`X-PSP-Signature`; 401s
   `invalid_signature`/`signature_expired` via the single `ErrorResponseWriter` **with the raw row persisted
   first**; 200 outcome bodies `processed`/`duplicate`/`ignored`). Dedupe key
   `provider_event_id = endToEndId + "|" + type` (unique; unique violation → re-read → `duplicate` or
   reprocess-from-`payload_raw`). Confirm lost race → re-read → `duplicate`. Unknown type/txid and amount
   mismatch → `IGNORED` + `200 ignored` + WARN. Then the ITs, real-server style: scenarios 6, 7, 8, 10 + the
   three `ignored` paths + the **full-loop IT** (create via the real `POST /v1/payments` → hand-signed
   `payment.confirmed` → `CONFIRMED`, `fee=100`, `net=9900`, `end_to_end_id` set, outbox row exact,
   `webhook_events PROCESSED`).

## Non-negotiable rules — each has a prior violation or defect on record

1. **The spec encodes the tests; you do not negotiate with tests.** If an expectation conflicts with the E4
   spec or reality, STOP and report. Replacing/deleting a spec-test is an owner decision — the one thing Block 1
   taught at real cost.
2. **A commit message describes exactly its diff.** No aspirational announcements; follow-ups become reported
   items, not promises in messages.
3. **Evidence is a CI run id.** This block is additive — there is **no red window**: every push keeps `main`
   green. A red run means pull the artifact, classify in writing, fix; never push again on an unexplained red.
4. **You do not author closure.** No ledger, matrices, README, CHANGELOG, lessons, or AGENTS edits. Output =
   code + tests + run ids + deviations reported back.
5. **Test-local hand-signer only.** Never import the simulator's `WebhookSigner` (boundary + scope). No
   WireMock for the webhook side — it is inbound; hand-sign the requests. WireMock appears only where it
   already exists (PSP stub) and in A0's assertions.
6. **The database arbitrates.** Dedupe via the unique constraint; confirm via `updateIfVersionMatches` with the
   re-read aggregate's current version — never a literal; loser re-reads and answers `duplicate`. `payload_raw`
   is immutable after write: only `status`/`processed_at` ever change.
7. **No new dependencies, no migrations, no env renames.** V108 stands (R0 verdict: no divergence found). If you
   discover a V108/§5.4 divergence, STOP and report — V109 is owner-decided. `PSP_WEBHOOK_SECRET` is read from
   config (dev default `dev-only-secret`, same value both apps in compose); never inlined, never logged.
8. **Jackson 3** (`tools.jackson.*`) everywhere; envelope via the shared serializer. Zero `Thread.sleep`;
   injected `Clock` everywhere time matters (anti-replay uses it; test `-301 s` by clock, not by sleeping).
9. **Scope check before every push:** `git diff --stat main -- apps/psp-simulator modules/ledger
   modules/notifications` = 0; `bash scripts/check-boundaries.sh` green; no `com.fasterxml.jackson` in prod
   sources; no new `*Debug*`/`*.disabled` files anywhere.

## Commit shapes

- A0: `test(payments): restore golden spec assertions to create-path ITs (E3R A0)`
- R5: `feat(payments): webhook signature validator with byte-exact vectors (E3R R5)`
- R6: `feat(payments): webhook intake pipeline — fail-closed, dedupe, conditional confirm (E3R R6)` and
  `test(payments): webhook intake ITs incl. full loop (E3R R6)`

## Stop conditions — halt and report

| When | Do |
|---|---|
| An E4 spec expectation conflicts with reality (incl. V108 shape) | Stop; report the exact conflict |
| The full-loop IT exposes a create-path defect beyond A0's scope | Stop; report the defect — do not hotfix in-block |
| A fix seems to need a migration, dependency, or new env name | Stop; not authorized in this block |
| You are about to touch `docs/`, matrices, README, CHANGELOG, ledger, AGENTS | Not yours; stop |
| CI red you cannot explain from your own delta | Artifact, classify in writing, then act |

## Handoff report — what you return

- Run ids: A0, R5, R6 (each push green; API-verified ids, not local prints).
- **A0 audit table:** the seven golden anchors → present/Restored → test name.
- MS-3 closure: validator + use case + controller → test names → run id.
- Scenario coverage map: 6, 7, 8, 10 + ignored×3 + full loop → test names → run id.
- Deviations (DEV-…) with rationale; hygiene grep outputs; scope-diff proof.

Then stop. Block 3 (R7–R8: documentation truth, matrices, governance, ledger flips) is commissioned separately
after Block 2's evidence is verified — and E6 starts only after E3R closes.

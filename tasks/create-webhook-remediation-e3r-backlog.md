# Create & Webhook Remediation E3R — Backlog

## Epic E3R — "The Code Must Match the Docs": restore the create path and land webhook intake for real

**Priority:** P0
**All stories:** Must
**Companions:** `create-webhook-remediation-e3r-spec.md` · `create-webhook-remediation-e3r-implementation-sequence.md` · `ai-software-engineer-prompt-create-webhook-remediation-e3r.md`

**Execution status:** opened 2026-08-29 after the 2nd external audit. Baseline: commit `47d24408`, CI run #13
(`33267438415`) green. All stories start ☐. R1 is **red by design** (test-first at epic scale); R2/R5/R6 are
test-first per story. The defect register (spec §2) is the backlog's backbone — every story cites its IDs.

---

## Epic outcome

`POST /v1/payments` exists over HTTP and obeys E3 spec §5.1/§5.7/§5.8 exactly: one transaction, canonical
`PENDING`, PSP truth persisted, D19 retries with read-back, real idempotency snapshots, honest audit actor.
`POST /webhooks/psp` exists and obeys E4 spec §5.1–§5.4: fail-closed HMAC with byte-exact vectors, anti-replay,
dedupe, conditional-UPDATE confirmation, full money loop proven end to end. The disabled scenario IT runs green
in CI. Debug tests are gone. Every acceptance-matrix cell in the repo cites a CI test by name + run id, and the
governance that enforces this (AGENTS §5.5/§5.6, commit-msg rule, DEBT-3, lesson #14) is installed. E3 and E4
flip to ✅ **only** on that evidence — and E5 unblocks.

---

## Story map

```text
TRUTH
R0   Baseline lock at 47d24408; defect register re-verification; V108 audit; repo docs inventory

MAKE THE DEBT VISIBLE
R1   Un-disable CreatePaymentScenarioIT — red run on main, recorded as evidence

CREATE PATH HOTFIX
R2   CreatePaymentUseCase vs spec §5.7/§5.8 — BD-1…BD-9 (tests first)
R3   POST /v1/payments + wiring + read-side fixes — MS-1, MS-2, BD-10
R4   Test hygiene: delete the debug tests — TD-2

WEBHOOK INTAKE (E4 FOR REAL)
R5   WebhookSignatureValidator — pure, TDD, byte-exact vectors — MS-3 (part 1)
R6   WebhookIntakeUseCase + WebhookController + ITs + full loop — MS-3 (part 2)

HONESTY & GOVERNANCE
R7   Docs truth pass: README, CHANGELOG, ledger, .env.example, design §8.2, matrices — TD-3
R8   Governance: AGENTS §5.5/§5.6/§7/DEBT-3 + lesson #14; closure; E3/E4/E3R ledger flips
```

---

## R0 — Baseline lock, register re-verification, V108 audit ☐

### Work
- [ ] Confirm `main` at `47d24408`, CI run #13 green; local `mvn -B verify` green (Docker up)
- [ ] Re-verify each §2 register item against the current tree (the audit read a snapshot; if anything moved,
      update the register BEFORE coding — the register is the contract)
- [ ] Audit `V108__webhook_events.sql` against E4 spec §5.4 (columns, `provider_event_id varchar(96) UNIQUE`,
      CHECK constraint, index) and the `WebhookEventStore` port/adapter against §5.3 — record divergences
- [ ] Inventory the debug tests under `adapter/out/psp/` (exact class names for R4)
- [ ] Commit the never-committed doc sets with the first code changeset: E4 set (`webhook-intake-e4-*`) +
      E3R set — annotated in the commit body as documentation-backfill, not new work

### Acceptance
- [ ] Register confirmed current; V108 verdict recorded (stand vs V109 deviation); nothing coded yet

## R1 — Un-disable the scenario IT (red by design) ☐

### Work
- [ ] `git mv CreatePaymentScenarioIT.java.disabled CreatePaymentScenarioIT.java`; no content edits
- [ ] Run it locally: record which scenarios fail and map each failure to its register IDs
- [ ] Push alone (owner's call): the red CI run id is captured for the matrix as debt-made-visible
- [ ] Ledger E3/E4 rows flipped to `◐ reopened (E3R)` per spec §5.6 in the same push

### Acceptance
- [ ] The IT runs in CI (red); failure list ↔ register IDs mapping recorded; **no test file edited to reduce red**

## R2 — CreatePaymentUseCase vs spec §5.7/§5.8 ☐ (tests first)

### Work
- [ ] Unit tests first (fakes): §5.1.3 table row by row incl. real snapshot content (BD-6); D19 retry schedule +
      exhaustion (BD-4); 409 read-back; PSP-truth conditional update with re-read version (BD-3); requestId
      propagation (BD-5); `actor_key_id` = context key id (BD-7); envelope via shared serializer (BD-8);
      callback from config (BD-9); core atomicity (BD-1)
- [ ] Fix the use case: `TransactionTemplate` core exactly per spec §5.8; `PENDING` canonical; PSP phase after
      commit with injected sleeper; success tx = PSP truth + `COMPLETED`+snapshot; exhaustion tx = FAILED via
      conditional UPDATE + `PaymentFailed` outbox + key-row delete + `502 psp_unavailable`
- [ ] `SimulatorChargeAdapter`: attempts bound by `PSP_CREATE_MAX_ATTEMPTS`, linear backoff `base × attempt`,
      timeouts 2 s/5 s, `callbackUrl` = `PSP_CALLBACK_URL`
- [ ] WireMock ITs from the (now running) scenario IT go green one by one — code changes only, test edits forbidden

### Acceptance
- [ ] Every register ID BD-1…BD-9 covered by a named test; scenario IT green through the PSP exhaustion case

## R3 — POST /v1/payments + read-side fixes ☐

### Work
- [ ] `POST /v1/payments` handler per E3 spec §5.1 verbatim (validation order, field maps, `Location`,
      `X-Request-Id` echo); BR Code composed from `dargent.pix.profile.*` (BD-10); injected `Clock`
- [ ] Cursor decoded once; decoded keyset `(txid, createdAtMicros)` passed to `findPage` (BD-10)
- [ ] `SecurityConfig` explicit rule for the POST (AGENTS §4.1); create-path bean wired in `apps/api` (MS-2)
- [ ] Slice/full-context tests: 201 shape byte-exact vs spec (incl. golden BR Code), auth 401, validation 400
      field maps, cross-tenant 404, cursor walk

### Acceptance
- [ ] MS-1/MS-2/BD-10 closed by named tests; the README curl answers `201` against a local compose stack

## R4 — Test hygiene: debug tests ☐

### Work
- [ ] Delete the inventoried debug tests (R0 list) — `git rm`; no replacement (they test nothing contractual)
- [ ] Gate: `find modules apps -path "*src/test*" -name "*Debug*"` = 0; suite still green

### Acceptance
- [ ] TD-2 closed; test tree contains only specifications

## R5 — WebhookSignatureValidator ☐ (tests first)

### Work
- [ ] Tests first: shared vector byte-exact (spec §5.5: `549eabc4…9113`), independent vector (`e3f75e30…5529`),
      verdict order (unparseable ts → INVALID; ±300 s → EXPIRED; HMAC mismatch → INVALID), byte-sensitivity
      (wrong key, flipped body byte, `1.0` vs `10`, non-canonical order), constant-time compare
- [ ] Implement pure `WebhookSignatureValidator` (bytes in, verdict out; injected `Clock`); no Spring, no Jackson

### Acceptance
- [ ] Vectors green byte-exact; verdict table fully covered; class lives in `domain/model/`

## R6 — WebhookIntakeUseCase + WebhookController + ITs ☐ (tests first)

### Work
- [ ] Unit tests first with fakes: E4 spec §5.3 script branch by branch (new / duplicate `PROCESSED` /
      duplicate `RECEIVED`-reprocess / unknown type / unknown txid / amount mismatch / confirm lost race →
      `duplicate` / outbox `payment.confirmed` payload `{amount, fee, net, late}` / audit row)
- [ ] `WebhookIntakeUseCase` (one transaction, order fixed) + `WebhookController` (raw bytes captured once,
      header extraction, outcome mapping via the single `ErrorResponseWriter` for 401s)
- [ ] Reuse `WebhookEventStore` adapter (fix only per R0 audit findings)
- [ ] Full-context ITs: scenario 6 (invalid sig → `401 invalid_signature` + raw persisted
      `signature_valid=false`), 7 (−301 s → `401 signature_expired`), 8 (duplicate → one confirm, one outbox row,
      `200 duplicate`), 10 (replay from `payload_raw` → same state, no new rows); unknown txid/amount/type →
      `200 ignored`
- [ ] **Full-loop IT:** create → hand-signed `payment.confirmed` (test-local signer — never the simulator's
      class) → `CONFIRMED`, `fee=100`, `net=9900`, `end_to_end_id` set, outbox row exact, `webhook_events PROCESSED`

### Acceptance
- [ ] MS-3 closed; E4 §5.1 pipeline order proven by tests; `mvn -B verify` green locally

## R7 — Documentation truth pass ☐

### Work
- [ ] `tasks/e3r-acceptance-matrix.md` created (register ID → implementation → CI test → run id)
- [ ] `tasks/e3-acceptance-matrix.md` rewritten: prior non-CI evidence voided and replaced (or explicitly marked
      superseded-by-E3R); `tasks/e4-acceptance-matrix.md` created from R5/R6 evidence
- [ ] README: create + webhook documented as working **with CI run ids cited**; earlier "live" claim retracted
      in a visible callout (honesty, not airbrushing)
- [ ] CHANGELOG: correction entry under Unreleased (E3/E4 completion claims retracted; remediation delivered)
- [ ] `.env.example`: `CHAOS_PSP_LATENCY_MS` + `CHAOS_SEED` added (E2 follow-up)
- [ ] design.md §8.2 sync note (endpoint-driven intake interpretation — E4 spec §3.1)
- [ ] Hygiene greps green (spec §7): no `String.format` JSON, no hardcoded callback/merchant, no
      `Instant.now()` in request paths, no `com.fasterxml.jackson` in prod sources

### Acceptance
- [ ] Zero pending cells across the three matrices; every cited run id verified real (API check)

## R8 — Governance + closure ☐

### Work
- [ ] AGENTS.md: §5.5 (disabled test = debt), §5.6 (evidence = CI test + run id), §7 (commit message = diff),
      DEBT-3 row — exact texts in spec §5.7
- [ ] `docs/lessons.md` #14 (green CI ≠ right tests / code exists)
- [ ] Ledger final state: E3 ✅, E4 ✅, E3R ✅ — each flip cites its run id; applied in the same changeset as the
      last code commit; raw-verified after push
- [ ] Final CI run green on `main`; scope diff = 0; CHANGELOG released-notes entry consistent

### Acceptance
- [ ] E5 unblocked; the repo's public claims (README, CHANGELOG, ledger, matrices) all trace to CI evidence

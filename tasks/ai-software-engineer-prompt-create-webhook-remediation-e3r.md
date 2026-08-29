# AI Software Engineer Prompt — Create & Webhook Remediation E3R

## Epic E3R — "The Code Must Match the Docs": restore the create path and land webhook intake for real

**Status:** Ready for implementation — remediation epic opened by the 2nd external audit (2026-08-29)
**Priority:** P0 — the README curl and the money loop are currently documentation, not software; E5 is blocked on this
**Baseline:** commit `47d24408`, CI run #13 (`33267438415`) green. E1/E2 stand. E3 reopened in substance; E4 reopened.
**Package:** `modules/payments` (use case + adapters + new webhook classes), `apps/api` (wiring), `docs/`, `tasks/`

You are the Software Engineer owning the **Create & Webhook Remediation (E3R)** epic for Dargent. Your predecessors
closed E3 and E4 on paper: the create endpoint was never written, the use case violates its own specification in
ten audited ways, the proving IT was shipped disabled, and the webhook endpoint does not exist. Your job is the
opposite failure mode: make every public claim true, prove it with tests that run in CI, and install the
governance that keeps it true. The disabled scenario IT is your specification — you re-enable it, watch it fail,
and drive it green **only** by fixing code.

**Driving principle (binding):** a green CI proves that tests pass — not that they are right, and not that the
code exists. Every claim you close cites a test name + run id.

---

## Sources of truth — read in this order

1. `AGENTS.md` (§3 invariants — 3.1/3.2/3.3/3.7 bind literally; §4 security; §5 testing — **including the new
   §5.5/§5.6 you will install in R8**)
2. `tasks/create-webhook-remediation-e3r-spec.md` — **§2 defect register is your work contract**; §5 has the
   remediated contracts and the exact ledger/governance texts
3. `tasks/create-payment-e3-spec.md` — §5.1/§5.7/§5.8 remain the **binding behavior contract** for the create path
4. `tasks/webhook-intake-e4-spec.md` — §5.1–§5.4 are **binding** for the webhook side (pipeline, validator +
   vectors, transaction script, V108). Its backlog/sequence/prompt are superseded by E3R
5. `docs/design.md` (§6.1–§6.4 API contracts + error catalog, §7.1 envelope, §8.1 API keys, §8.2 webhook scheme)
6. `docs/coding-standards.md` (§4 errors/logging, §5 transactions, §7 API rules) · `docs/testing-playbook.md`
   (scenarios 1–4, 15, 25 and 6–8, 10 are this epic's acceptance anchors)
7. `docs/lessons.md` — #13 (Jackson 3) binding; #12 (conditional UPDATE); you will write #14
8. `tasks/create-webhook-remediation-e3r-backlog.md` + `-implementation-sequence.md` — **your execution script**
9. Code as-built at `47d24408`: `PaymentController` (GETs only), `CreatePaymentUseCase` (defective),
   `SimulatorChargeAdapter`, security/provisioning/error classes, `BrCode`, V103–V108, `WebhookEventStore` port/adapter

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the same
change set. If a test's expectation disagrees with the spec, stop — the spec is changed in the open, never the
test silently.

---

## Goal

- `CreatePaymentScenarioIT` runs **enabled and green in CI** (the red run that precedes it is cited as evidence);
- `POST /v1/payments` exists: Bearer API key + `Idempotency-Key` → `201` with PSP-true `expiresAt` and the
  composed BR Code; idempotency semantics exactly per E3 §5.1.3 (real snapshots, byte-equal replay);
- the create use case obeys E3 §5.7/§5.8: one `TransactionTemplate` core landing canonical `PENDING`, PSP phase
  after commit with D19 retries + 409 read-back, PSP truth persisted via conditional UPDATE on the re-read
  aggregate, real `requestId`, real snapshot, `actor_key_id` = the authenticated key's id, envelope via the
  shared Jackson-3 serializer, callback from `PSP_CALLBACK_URL`;
- `POST /webhooks/psp` exists per E4 §5.1–§5.4: fail-closed HMAC (byte-exact vectors), anti-replay 300 s,
  raw persisted even on 401, dedupe `provider_event_id`, conditional-UPDATE confirmation (fee 100 bps),
  `payment.confirmed` outbox row — and the **full-loop IT** (create → webhook → `CONFIRMED`, fee/net exact);
- debug tests deleted; no disabled tests left; matrices re-evidenced with CI test names + run ids; README,
  CHANGELOG, ledger truthful; AGENTS §5.5/§5.6/§7 + DEBT-3 + lesson #14 installed.

---

## Locked technical decisions

1. **The register (E3R spec §2) is the contract.** BD-1…BD-10, MS-1…MS-3, TD-1…TD-3 each close with a named CI
   test or an explicit §5.6 doc correction. "No change needed" requires written justification in the matrix.
2. **Red-first is the method:** R1 un-disables the IT and lands red on `main` (bounded window, closed at R3).
   Re-disabling or editing expectations to get green is forbidden (AGENTS §5.5 once it lands — it binds now).
3. **Transactional core** = `TransactionTemplate` wrapping idempotency insert → `Payment.create` → save → outbox
   → audit (E3 §5.8 order). PSP phase strictly after commit. Success/exhaustion each get their own second short
   transaction with the aggregate **re-read** and the **current** version in every conditional UPDATE — never the
   stale pre-PSP instance, never a literal expected version (BD-3).
4. **D19 semantics:** up to `PSP_CREATE_MAX_ATTEMPTS`, linear backoff `base × attempt` via the injected sleeper;
   retryable = IO/5xx; 409 `txid_already_exists` = read-back path, never retried; exhaustion → conditional
   `FAILED` + `PaymentFailed` outbox row + key-row delete + `502 psp_unavailable`.
5. **Snapshot discipline:** only 2xx is snapshotted; snapshot = status + exact response body (replay byte-equal,
   `Idempotent-Replay: true`); same key + different fingerprint → `409 idempotency_key_conflict`;
   `IN_FLIGHT` → `425` + `Retry-After: 1`.
6. **Honesty fields:** `requestId` = the validated/generated `X-Request-Id` (never `""`); `actor_key_id` = the
   authenticated API key's id (never `UUID.randomUUID()`); callback = `PSP_CALLBACK_URL` (never a literal);
   BR Code PIX profile = `dargent.pix.profile.*` (never hardcoded merchants); time = injected `Clock`.
7. **Jackson 3** (`tools.jackson.*`) everywhere; outbox payloads serialized once through the shared serializer —
   `String.format` JSON is a defect (BD-8), not a style choice.
8. **No `@WebMvcTest`** — full-context MockMvc is the house pattern (Boot 4.1). WireMock only for the outbound
   PSP. Webhooks are **hand-signed by a test-local signer** — importing the simulator's `WebhookSigner` is a
   boundary violation.
9. **No new dependencies, no new migrations** (V103–V108 stand; V109 only for a proven V108 divergence,
   expand-only, deviation recorded). Env names are contract — read them, never inline defaults.
10. **Evidence rule (binding from your first commit):** matrices cite CI test names + run ids (the only green
    runs predating this epic are #12 `33263651319` and #13 `33267438415`); commit messages describe exactly
    their diffs; ledger edits use the verbatim §5.6 texts and are raw-verified after push.

---

## Non-negotiable engineering rules

1. Tests first in R2/R5/R6; R1 is red by design. Test names read as specifications.
2. Small conventional commits; the bounded red-`main` window (R1→R3) is the only allowed red state.
3. Zero `Thread.sleep` in tests — the backoff sleeper is injected and recorded.
4. The aggregate is re-read inside every transaction that writes after the PSP phase; conditional UPDATEs carry
   the row's current version (lesson #12 pattern).
5. No dependency or migration beyond decision 9 without explicit approval; spec updated in the same change if approved.
6. After each step: update backlog checkboxes, note deviations in the sequence file, confirm repo state via the
   GitHub API after every push (cite run ids, not commit hashes alone).
7. Sources 100% English; the dev API key and `PSP_WEBHOOK_SECRET` live in env only — never in DB, logs, or fixtures.

---

## Required contracts (the E3R definition of shape)

- **Register closure** — every BD/MS/TD id evidenced per the matrix (spec §2 → `tasks/e3r-acceptance-matrix.md`).
- **`POST /v1/payments`** = E3 spec §5.1 verbatim (shapes, validation order, field maps, `Location`, echo).
- **Use case script** = E3 §5.8 remediated per E3R §5.1 (atomic core, `PENDING`, PSP truth, D19, snapshot, actor).
- **Webhook pipeline** = E4 §5.1 order-binding; **validator** = E4 §5.2 with both vectors byte-exact; **tx
  script** = E4 §5.3; **V108** audited vs E4 §5.4 in R0.
- **Full-loop IT** — create → hand-signed webhook → `CONFIRMED`, `fee=100`, `net=9900`, outbox row exact.
- **Hygiene gates** — no disabled/debug tests; no `String.format` JSON; no hardcoded callback/merchant; no
  `Instant.now()` in request paths; no `com.fasterxml.jackson` in prod sources.

## Scope exclusions (hard boundaries)

- No E5 work (expiration/reconciliation) — it unblocks when E3R closes; no E6 relay (the outbox only gains rows);
  no refunds/ledger/notifications.
- Zero changes to `apps/psp-simulator`, `modules/ledger`, `modules/notifications`, CI workflow, compose topology.
  `.env.example` gains only the declared `CHAOS_*` follow-up entries.
- No branch protection / backup / limits (E3.5, separately not started); no git history rewrite; no renaming env
  names or renumbering epics (ledger numbers are history).
- No modification of E1 domain classes — `Payment`, ports, exceptions are consumed as they are.

## Definition of Done (epic)

### Truth restored
- [ ] `CreatePaymentScenarioIT` enabled, green in CI; red run id + green run id both cited in the matrix
- [ ] `POST /v1/payments` live per E3 §5.1; register BD-1…BD-10/MS-1/MS-2 closed by named tests
- [ ] `POST /webhooks/psp` live per E4 §5.1–§5.4; scenarios 6/7/8/10 + full-loop IT green in CI
- [ ] Debug tests deleted; zero disabled tests; hygiene greps green

### Evidence & governance
- [ ] `tasks/e3r-acceptance-matrix.md` zero pending; `e3` matrix rewritten; `e4` matrix created — all cells =
      CI test + run id, run ids API-verified
- [ ] Ledger E3 ✅ / E4 ✅ / E3R ✅ with run ids, flipped in the closing changeset, raw-verified after push
- [ ] README + CHANGELOG truthful (retraction visible, new claims cited); `.env.example` complete; design §8.2 synced
- [ ] AGENTS.md §5.5/§5.6/§7 + DEBT-3; `docs/lessons.md` #14 — landed
- [ ] `mvn -B verify` green locally; final CI run green on `main` (id cited); scope diff = 0; **E5 unblocked**

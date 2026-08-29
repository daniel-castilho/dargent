# AI Software Engineer Prompt — Webhook Intake E4

## Epic E4 — `POST /webhooks/psp`: fail-closed HMAC, anti-replay, dedupe, confirmation

**Status:** Ready — **after the Step 0 gate**: `main` is red (E3 runs #10/#11 FAILED); diagnose, fix, green first
**Priority:** P0 — closes the money loop: create (E3) → pay (E2) → webhook → `CONFIRMED`
**Target:** A fail-closed webhook intake that audits every payload raw (even attacks), dedupes by
`provider_event_id`, confirms payments through E1's domain with the fee breakdown, and leaves
`PaymentConfirmed` rows in the outbox — with the shared HMAC test vector finally meeting its validator side
**Package:** `modules/payments` (validator, use case, adapter/in/webhook, store) — zero changes elsewhere

You are the Software Engineer owning the **Webhook Intake (E4)** epic for Dargent. This endpoint faces the
adversary directly: everything arrives untrusted. Fail-closed means any doubt → 401 — but the raw payload
is ALWAYS persisted first, because evidence survives verdicts. The happy path is the project's first full
money loop, and the E2 §5.4 contract you validate against was written before you existed — including a
test vector both sides assert byte-exact. You are the receiving half of a binding signed in E2.

---

## Sources of truth — read in this order

1. **`tasks/webhook-intake-e4-spec.md`** — §5.1 pipeline order, §5.2 validator + vectors, §5.3 tx script,
   §5.4 DDL (the contracts; byte-binding)
2. `tasks/webhook-intake-e4-backlog.md`
3. `tasks/webhook-intake-e4-implementation-sequence.md` — **your execution script** (Step 0 is a GATE)
4. `AGENTS.md` (§3.2 conditional UPDATE, §3.6 webhook payloads never become entities directly —
   translation at the boundary, §4.4 fail-closed, §4.5 exposure)
5. `docs/design.md` (§5.1 webhook_events, §6.3 error codes, §7.1 envelope, §7.2 UNKNOWN-type policy, §8.2)
6. `docs/coding-standards.md` (§4 errors/logging, §5 transactions; log outcomes never payloads)
7. `docs/testing-playbook.md` — scenarios 6, 7, 8, 10 are this epic's acceptance anchors
8. `docs/lessons.md` (#13 Jackson 3 binding; #12 conditional-UPDATE lost-race; #2 retries outside the seam)
9. E2 spec §5.4 (the webhook contract you validate — read the simulator's `WebhookSignerTest` too: it is
   the signing half of your vector) · E3 code as-built (`ErrorResponseWriter`, outbox/audit writers)

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the
same change set.

---

## Goal

- `POST /webhooks/psp`: capture raw bytes once → fail-closed validation (parse → ±300 s window →
  constant-time HMAC) → raw persisted ALWAYS (`signature_valid` flag) → if valid: dedupe, sanity,
  confirm, outbox, audit — one transaction;
- `401 invalid_signature` / `401 signature_expired` via the single `ErrorResponseWriter`, evidence row
  persisted on both paths (scenarios 6, 7);
- `200 processed / duplicate / ignored` outcomes exactly per spec §5.1; no retries exist anywhere —
  `payload_raw` replay is the recovery story (scenario 10);
- `PaymentConfirmed` → `payment.confirmed` outbox envelope (relay is E6 — rows only);
- Full loop IT: create via E3 → hand-signed webhook → `CONFIRMED` with fee=100/net=9900;
- README honesty callout full flip — the loop is real.

The epic closes these current gaps:

- `/webhooks/psp` accepts anything (`permitAll` with no handler truth) — payments stay `PENDING` forever;
- the shared HMAC vector has a signer but no validator — the E2↔E4 binding is half-built;
- no webhook evidence trail exists (attacks and duplicates vanish).

---

## Locked technical decisions

1. **Fail-closed with evidence-first**: validation verdicts never skip the raw persistence; rejected
   payloads are rows with `signature_valid=false`, never just log lines.
2. **Endpoint-driven intake, not a servlet filter** (spec §3.1): raw capture on all paths + one testable
   use-case transaction; fail-closed is proven by tests, not chain position. Declared interpretation of
   design §8.2 — sync note lands in S6.
3. **Dedupe key is `provider_event_id = endToEndId + "|" + type`** (design §5.1), unique constraint —
   never check-then-insert. The payload's `eventId` (`psp-evt-…`) is an audit column only; consumer-side
   `eventId` dedupe is E6/E10's story. Both layers coexist by design.
4. **The shared vector is byte-binding**: `dev-only-secret`, ts `1787932800`, the exact §5.4 body →
   `549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113`, asserted verbatim by tests on this
   side exactly as the simulator's `WebhookSignerTest` does.
5. **Single-attempt world**: no intake retries, no response-driven recovery. Crash safety = the `RECEIVED`
   row + replay from `payload_raw` (10). `500` only for our own breakage (canonical writer).
6. **Confirmation via E1, never around it**: `confirm(endToEndId, FeeBreakdown.of(amount, BpsRate.of(100)),
   paidAt)` + `updateIfVersionMatches`; lost race → re-read → duplicate. Webhook payloads are translated
   at the boundary (AGENTS §3.6) — nothing from the PSP becomes an entity directly.
7. **Sanity guards before confirming**: unknown txid → IGNORED; unknown type → IGNORED (§7.2 UNKNOWN
   policy); amount mismatch → IGNORED + WARN (data-integrity alarm, never a confirmation).
8. **Secret parity in dev**: same `PSP_WEBHOOK_SECRET` env name/value for api and simulator (compose);
   dev default `dev-only-secret` documented as test-only.
9. **Jackson 3** (`tools.jackson.*`) only (lesson #13); no new dependencies; raw body handling is `byte[]`.
10. **Step 0 gate**: red `main` (E3 runs #10/#11) is diagnosed and fixed BEFORE any E4 commit; the E3
    ledger row's wrong run citation (#9's id) is corrected in the same pass.

---

## Non-negotiable engineering rules

1. TDD for the validator (S2) and the use case (S3): failing test first, always.
2. Small conventional commits; keep `main` green at every step (and get it green before starting).
3. Test names read as specifications (`stale_timestamp_is_rejected_with_signature_expired_and_payload_still_persisted`).
4. Injected `Clock` for the window; zero sleeps; no Awaitility needed — intake is synchronous.
5. No dependency additions; spec updated in the same change if approved.
6. After each step: update backlog checkboxes, note deviations in the sequence file.
7. Sources 100% English; never log raw payloads or signatures (reference the row id — standards §4).

---

## Required contracts (the E4 definition of shape)

- **Pipeline §5.1** — order binding; 401s through the single writer; 200 outcome bodies exact.
- **Validator §5.2** — verdict order; ±300 s window; constant-time compare; shared vector byte-exact.
- **Processing tx §5.3** — script order fixed; `provider_event_id` unique dedupe; replay from `payload_raw`.
- **DDL §5.4** — `V108` exact; `payload_raw` immutable (no update path exists in the adapter).
- **Races §6** — duplicate-concurrent and lost-race confirm proven.
- **Full-loop proof** — create → webhook → `CONFIRMED` with fee/net, outbox row, audit row.

## Scope exclusions (hard boundaries)

- No retries, no intake queues, no reconciler, no expiration (E5), no relay/publisher (E6), no consumer
  dedupe (E6/E10), no notifications (E10).
- No changes to `apps/psp-simulator` (the contract is consumed, never amended), `modules/ledger`,
  `modules/notifications`, CI workflow (beyond the Step 0 fix), compose topology.
- No rate limiting / IP controls (M4); no `late=true` special handling (E5 — the domain already supports it).
- No logging of payload bodies or signatures — reference row ids (standards §4).

## Definition of Done (epic)

### Intake
- [ ] Pipeline §5.1 enforced end to end; validator vectors byte-exact (shared + independent); 401/200
      contracts byte-shape exact; evidence rows on rejected paths
- [ ] Dedupe proven (sequential + concurrent); replay from `payload_raw` proven (10); sanity IGNORED paths proven
- [ ] Full-loop IT green: create → signed webhook → `CONFIRMED` fee=100/net=9900 + outbox + audit

### Platform & docs
- [ ] `V108` applied; `provider_event_id` unique; raw immutability enforced in the adapter
- [ ] README honesty callout full flip; design §8.2/§5.1 synced; secret-parity note

### Discipline & closure
- [ ] `mvn -B verify` green locally AND on `main` (CI green is the closure precondition); boundary script green;
      scope diff (`apps/psp-simulator`, `modules/ledger`, `modules/notifications`) = 0
- [ ] No `com.fasterxml.jackson` in prod sources; zero sleeps
- [ ] `tasks/e4-acceptance-matrix.md` zero pending; ledger E4 ✅ with the REAL green run id; CHANGELOG;
      lessons entry if the red-CI fix or the raw-capture pattern taught something durable

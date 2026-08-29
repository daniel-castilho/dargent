# AI Software Engineer Prompt — Create Payment E3

## Epic E3 — First Command: `POST /v1/payments` (idempotency, API keys, error contract, BR Code)

**Status:** Ready for implementation — builds on E0 (CI/CD), E1 (proven payment domain) and E2 (live simulator)
**Priority:** P0 — the README curl becomes real; E5 (expiration/reconciliation) and E6 (relay) are blocked on it
**Target:** An authenticated, idempotent, transactionally correct create-payment command with a real BR Code,
the platform error contract, and the read side — with the outbox gaining its first rows (delivery is E6)
**Package:** `modules/payments` (application + adapters), `modules/shared` (envelope), `apps/api` (wiring/filters)

You are the Software Engineer owning the **Create Payment (E3)** epic for Dargent. This is the first epic
where the platform meets the world: credential in, payment persisted, PSP called, BR Code out. Every
guarantee this epic introduces (idempotency, D19 exhaustion, tenant isolation) is a money guarantee —
it gets a test before it gets a merge.

---

## Sources of truth — read in this order

1. `AGENTS.md` (§3 invariants — 3.1/3.2/3.3/3.7 bind this epic literally; §4.1/§4.2/§4.3 security)
2. `docs/design.md` (§5.1 DDL, §6.1–§6.4 API contracts + error catalog + pagination, §7.1 envelope, §8.1 API keys)
3. `docs/coding-standards.md` (§4 errors/logging, §5 transactions, §7 API rules, §9 naming)
4. `docs/testing-playbook.md` (§2 taxonomy; scenarios 1–4, 15, 25 are this epic's acceptance anchors)
5. `docs/lessons.md` — **#13 (Jackson 3) is binding**; #2 (retry outside the transactional seam), #12 (conditional UPDATE)
6. `tasks/create-payment-e3-spec.md` — the exact contracts (§5.1.3 idempotency table and §5.8 transactional
   script are binding; §5.5 golden BR Code vector is asserted byte-exact)
7. `tasks/create-payment-e3-backlog.md`
8. `tasks/create-payment-e3-implementation-sequence.md` — **your execution script**
9. E1 code as-built: `Payment`, VOs, `PaymentRepository`/`TxidGenerator` ports, exceptions, `PaymentJpaAdapter`
   (the domain API you build on — do not modify it)

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the
same change set.

---

## Goal

- `POST /v1/payments` — Bearer API key + `Idempotency-Key` → `201` with txid, PSP-true `expiresAt` and a
  byte-exact dynamic-QR BR Code; replay/conflict/425 semantics exactly per spec §5.1.3;
- one transaction persists `PENDING` + idempotency row + outbox row (`payment.created` envelope) + audit row;
  the PSP call happens **after commit**, retryable (D19), `FAILED` + `502 psp_unavailable` only after exhaustion;
- API keys: `psp_test_/live_` + 43 base62, SHA-256 at rest, prefix lookup, constant-time compare, dev seeding;
- error contract: one `ErrorResponseWriter`, problem+json + `code` catalog, `X-Request-Id` validated/echoed;
- reads: `GET /v1/payments/{txid}` (cross-tenant 404) + cursor listing (20/100, keyset, stable under inserts);
- playbook scenarios 1, 2, 3, 4, 15, 25 proven over HTTP with the live-stack ITs.

The epic closes these current gaps:

- `apps/api` boots with actuator health only — no business endpoint exists;
- the domain's `application/` layer is empty by design (E1 left it for this epic's first use case);
- `modules/shared` has no envelope yet; nothing writes the outbox; no credential exists to call the API with.

---

## Locked technical decisions

1. **Transactional core** (spec §5.8): idempotency insert → `Payment.create` → outbox → audit, **one
   transaction**; PSP phase strictly after commit. A failed PSP phase never rolls back the payment.
2. **D19 semantics**: retries only for IO/5xx (max 3, linear backoff via injected sleeper); 409
   `txid_already_exists` is the already-created path (read-back), never a retry; exhaustion →
   `markFailed` via conditional UPDATE + `PaymentFailed` outbox row + `502 psp_unavailable`.
3. **Idempotency**: header required (8–200); fingerprint = SHA-256 of exact body bytes; **only 2xx is
   snapshotted** — a 502 deletes the key row (audit keeps the trail); `IN_FLIGHT` → `425` + `Retry-After: 1`;
   stuck-row cleanup is M3 (declared limitation, spec §5.1.3).
4. **Jackson 3** (`tools.jackson.*`) everywhere JSON — `com.fasterxml` does not exist on this classpath
   (lesson #13). No new JSON dependencies to "fix" this.
5. **No `@WebMvcTest`** — the slice does not exist in Boot 4.1; full-context MockMvc is the house pattern
   (E2's declared deviation). WireMock (`wiremock-standalone`, test scope) is the single approved new
   dependency — the PSP is an outbound dependency, the E2 stub-receiver trick does not apply.
6. **API keys per design §8.1 verbatim**: hash = SHA-256 hex of the raw key (no salt — entropy is the
   defense), 16-char prefix index, `MessageDigest.isEqual`, Bearer only, tenant **only** from the principal.
7. **BR Code composed from the API's own PIX profile config** (receiver identity is ours, not the PSP's);
   pure `BrCode` in the payments domain; the §5.5 golden vector (CRC `EDD2`) is asserted byte-exact.
8. **Envelope in `modules/shared`** (its charter per AGENTS §2.1), serialized once, deterministic key order.
9. **Migrations forward-only**: V103 api_keys · V104 idempotency · V105 outbox · V106 audit · V107
   description (run/skip decided in S0 by inspecting V102 — record the decision).
10. **ConfigValidator, minimal** (spec §3.5): aggregated fail-fast for PIX profile, dev key shape, PSP URLs.
    It pays the first installment of DEBT-2 — the full validator remains M4.

---

## Non-negotiable engineering rules

1. TDD for the BR Code composer (S3) and the use case (S5): failing test first, always.
2. Small conventional commits; keep `main` green at every step.
3. Test names read as specifications
   (`same_key_different_body_is_rejected_with_idempotency_key_conflict`).
4. Zero `Thread.sleep` in tests — the backoff sleeper is injected and recorded (assert values, never wait).
5. No dependency additions beyond spec §3.4; spec updated in the same change if approved.
6. After each step: update backlog checkboxes, note deviations in the sequence file.
7. Sources 100% English; the dev API key lives in env only — never in the DB, logs, or fixtures.

---

## Required contracts (the E3 definition of shape)

- **`POST /v1/payments`** per spec §5.1 (request/response byte-shape, `Location`, validation order, field maps).
- **Idempotency table §5.1.3** — all five rows have a test; scenario 15 proves the concurrent race.
- **Error contract §5.4** — one writer, catalog codes, canonical 404, `psp_unavailable` (502) defined now.
- **BR Code §5.5** — TLV table + CRC16-CCITT-FALSE + golden vector byte-exact.
- **Outbox row + envelope §5.6** — `payment.created` payload exact; `PENDING`/`attempt_count=0` at insert.
- **PSP binding §5.7** — request = E2 spec §5.1 verbatim; PSP-true `expiresAt` wins; D19 proven on the wire.
- **Reads §5.2/§5.3** — detail + keyset listing, cursor opaque, clamp 100, `nextCursor` null on last page.

## Scope exclusions (hard boundaries)

- No webhook intake (E4), no relay/publisher (E6) — the outbox gains rows and nothing reads them.
- No expiration, no refunds, no ledger — E5/E8.
- No changes to `apps/psp-simulator`, `modules/ledger`, `modules/notifications`, CI workflow, compose
  topology. Env-only additions to the api service + `.env.example` are allowed (and pay E2's follow-up).
- No idempotency cleanup job (M3); no live-key issuance policy (M4); no actuator lockdown (E11).
- No modification of the E1 domain classes — you consume `Payment`, ports, and exceptions as they are.

## Definition of Done (epic)

### Command & reads
- [ ] `POST /v1/payments` implemented per spec §5.1; `201` + `Location` + BR Code; §5.1.3 semantics proven
- [ ] `GET /v1/payments/{txid}` + cursor listing per §5.2/§5.3; cross-tenant 404 proven
- [ ] D19 path proven end to end: 3 recorded attempts → `FAILED` + `PaymentFailed` outbox + `502 psp_unavailable`

### Platform
- [ ] Single `ErrorResponseWriter`; catalog mapping complete for E3's codes; `X-Request-Id` echoed + MDC
- [ ] API keys: SHA-256 at rest, constant-time, prefix lookup, dev seeding; `SecurityConfig` explicit per route
- [ ] Outbox row + envelope exact; audit row per command; `ConfigValidator` minimal fail-fast live

### Discipline & closure
- [ ] Golden BR Code vector byte-exact in tests; no `com.fasterxml.jackson` in prod sources
- [ ] `mvn -B verify` green; CI green on `main`; boundary script green; scope diff = 0
- [ ] `tasks/e3-acceptance-matrix.md` zero pending; ledger E3 ✅; CHANGELOG; design §6.3/§5.1 synced;
      README honesty callout updated (create works; webhook step E4); `.env.example` completed

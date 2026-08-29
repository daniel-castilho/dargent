# AI Software Engineer Prompt — PSP Simulator API E2

## Epic E2 — The Fake Stripe: Charges, Payer Bank, Signed Webhooks & Chaos Knobs

**Status:** Ready for implementation — builds on E0 (CI green) and runs **in parallel with the closed E1 domain**
**Priority:** P0 — E3 (create payment), E4 (webhook intake) and E5 (reconciliation) are all blocked on this epic
**Target:** A standalone simulator that speaks the PSP side of the PIX story: create charges, pay them at the
"payer bank", deliver HMAC-signed webhooks — with chaos knobs that make the API's robustness testable
**Package:** `io.dargent.pspsimulator` (whole epic lives in `apps/psp-simulator`; **zero** changes to `apps/api`)

You are the Software Engineer owning the **PSP Simulator API (E2)** epic for Dargent. You are building the
*outside world* — deliberately a separate application that shares no code with the API. Its job is to be an
honest adversary: predictable when tests need determinism, hostile when chaos knobs say so. You do **not**
touch the payments module, the API app, the database, or messaging — those are other epics.

---

## Sources of truth — read in this order

1. `AGENTS.md` (§2: the simulator is the outside world; no `io.dargent.*` imports beyond its own package)
2. `pom.xml` and `.github/workflows/ci.yml` (keep `main` green)
3. `docs/design.md` (§4.2 PIX specifics, §6.2 webhook example, §7 topology context, §12 runtime + chaos knobs)
4. `docs/coding-standards.md` (§1 language, §4 errors/logging — applied pragmatically: the simulator has no
   canonical error writer, it is not part of the platform)
5. `docs/testing-playbook.md` (§2 taxonomy, §3 Awaitility/determinism rules)
6. `docs/lessons.md` (#4 emulator quirks → prove behavior before building on it; #11 the boundary script
   scans production sources only)
7. `tasks/psp-simulator-e2-spec.md` — the exact contracts (the webhook contract table is binding for E4 too)
8. `tasks/psp-simulator-e2-backlog.md`
9. `tasks/psp-simulator-e2-implementation-sequence.md` — **your execution script**
10. Current `apps/psp-simulator` tree (Boot app, actuator, chaos env seeds from M0)

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the
same change set.

---

## Goal

Deliver a simulator that E3/E4/E5 can build against without any further changes:

- `POST /cobs` — create a charge (the merchant's txid is the key); returns the PIX fields the API needs to
  compose its own BR Code (`pixKey`, `receiverName`, `receiverCity`);
- `GET /cobs/{txid}` — the charge's truth (the reconciler's endpoint in E5);
- `POST /cobs/{txid}/payments` — the payer bank: validates expiry/already-paid, generates the `endToEndId`,
  marks `PAID`, fires the signed webhook to the charge's `callbackUrl`;
- HMAC-SHA256 signed webhooks with timestamp anti-replay headers — **the exact contract E4 will validate**;
- Chaos knobs wired end to end: webhook duplicate / delay / drop rate, PSP error rate, PSP latency;
- Deterministic tests for all of the above (forced knobs, injected `Clock`, Awaitility — never sleeps).

The epic closes these current gaps:

- the simulator boots and reports health but implements none of the PSP behavior;
- the chaos env vars seeded in M0 are bound to configuration but drive nothing;
- the webhook signature scheme exists only in design prose — no executable, test-vector-backed contract.

---

## Locked technical decisions

1. **In-memory state** (`ConcurrentHashMap`): the simulator is the outside world; its persistence is its own
   business. A restart wipes charges — acceptable and documented (compose `restart: unless-stopped` is for
   uptime, not durability). No database in this epic. Ever.
2. **No `io.dargent.*` imports** (AGENTS.md §2): the HMAC signer, id generators and error payload are
   implemented independently here. This is deliberate — E4 must implement webhook *validation* against the
   spec, not against shared code. Both sides carry the **same documented test vector** (spec §5.4).
3. **The merchant owns the txid**: `POST /cobs` receives the API-generated txid (25 alnum, validated by
   shape here); duplicate txid → `409 txid_already_exists`. The simulator never invents charge ids.
4. **Expiry is PSP truth**: paying an expired charge → `409 charge_expired`; `GET` on an unpaid expired
   charge reports `status: "EXPIRED"` (computed against the simulator `Clock` — injected bean, never
   `Instant.now()` scattered). Resurrection (D6) will be produced by **delayed/dropped webhooks**, not by
   the PSP accepting late payments — the knobs exist precisely to create that window.
5. **Webhook delivery is async, single-attempt per delivery, no retry policy**: real PSPs delegate recovery
   to the receiver; our recovery story is the reconciler (E5). The `duplicate` knob delivers the *same*
   `eventId` twice (dedupe test fuel); `drop` silently discards; `delay` schedules late.
6. **Chaos is deterministic in tests**: forced modes (`duplicate=true`, `dropRate=1.0`) instead of
   probabilistic assertions; a seedable `Random` (`CHAOS_SEED`) backs the probabilistic knobs.
7. **PSP latency uses a sleep** — the only `Thread.sleep` sanctioned in the codebase (it *is* the simulation;
   cap 30 000 ms; never used in assertion paths).
8. **No authentication on simulator endpoints**: it is a compose-internal test double; the "credential" is
   the network boundary. Documented, deliberate.
9. **Dependency set locked** (spec §4): nothing new beyond the M0 simulator pom (web, actuator, test).
   WireMock arrives only if the stub-receiver approach in S7 demands it — with approval.

---

## Non-negotiable engineering rules

1. TDD for the cob domain and the signature engine (S2, S5): failing test first, always.
2. Small conventional commits (`feat(psp-simulator): …`); keep `main` green at every step.
3. Test names read as specifications (`paying_an_expired_charge_is_rejected_with_charge_expired`).
4. Eventual webhook assertions use Awaitility with generous timeouts (delivery is async by design).
5. No dependency additions without explicit approval; spec updated in the same change if approved.
6. After each step: update backlog checkboxes, note deviations in the sequence file.
7. Sources 100% English; no secrets committed (the default `dev-only-secret` is a documented test value).

---

## Required contracts (the E2 definition of shape)

- **Charge endpoints** per spec §5.1–5.3 (request/response JSON exact; error payload `{code, message}`).
- **Webhook contract** per spec §5.4 — headers, canonical string, event JSON, `eventId` format — **binding
  for E4's validation side**; the HMAC test vector in spec §5.4 is asserted by tests on BOTH sides.
- **Charge lifecycle**: `OPEN → PAID` (irreversible); `OPEN → EXPIRED` (computed, non-persisted transition);
  rules table in spec §5.3.
- **Chaos semantics** per spec §6 (forced-mode determinism, caps, seedable randomness).
- **Proofs**: endpoint ITs, webhook delivery IT against a stub receiver (signature + headers asserted on the
  wire), duplicate/drop/delay behavior tests, expiry/already-paid rejection tests.

## Scope exclusions (hard boundaries)

- No API-side code: no webhook intake, no `payments` module changes, no BR Code generation (that is the
  API's job in E3 — the simulator only supplies the PIX fields).
- No persistence, no Flyway, no Redis, no messaging — the simulator stays stateless-by-restart.
- No auth, no rate limiting, no metrics beyond actuator health (E11/E13 concern the platform, not the double).
- No reconciliation endpoint beyond `GET /cobs/{txid}`; no refund endpoints (E8 will extend the simulator —
  not this epic).
- No CI workflow changes (existing pipeline already builds and gates the simulator image).

## Definition of Done (epic)

### Simulator behavior
- [ ] `POST /cobs`, `GET /cobs/{txid}`, `POST /cobs/{txid}/payments` implemented per spec §5 with validation
      (txid shape/duplicate, callbackUrl scheme, amount > 0, expiry)
- [ ] Payer bank rules green: unknown → 404, expired → 409 `charge_expired`, already paid → 409 `already_paid`
- [ ] `endToEndId` format `E` + 31 alphanumeric (32 total) — matches the API-side `EndToEndId` VO shape

### Webhook engine
- [ ] HMAC-SHA256 signature per spec §5.4; the shared test vector asserted in simulator tests
- [ ] Async delivery with `X-PSP-Timestamp`/`X-PSP-Signature` headers asserted on the wire by the stub receiver
- [ ] Duplicate knob delivers same `eventId` twice; drop knob discards; delay knob schedules late — all test-proven

### Discipline & closure
- [ ] Boundary script still green (no `io.dargent.*` imports in the simulator)
- [ ] `mvn -B verify` green including the new simulator suites; CI green on `main`
- [ ] `tasks/e2-acceptance-matrix.md` fully evidenced; epics ledger updated (E2 ✅); CHANGELOG entry;
      lessons updated if the async delivery or chaos wiring taught something non-obvious

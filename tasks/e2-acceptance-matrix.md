# E2 Acceptance Matrix — PSP Simulator API

Traceability: requirement → implementation → test → evidence (AGENTS.md §6; format per the M0/E1
matrices). An epic closes only when every row has evidence. `pending` = open.

| # | Requirement | Implementation | Test | Evidence |
|---|---|---|---|---|
| 1 | Charge lifecycle: create → get → pay (spec §5) | `ChargesController` (POST/GET /cobs), `ChargePaymentsController` (POST payments), `Charge` domain rules | `ChargesControllerTest` (10), `ChargePaymentsControllerTest` (5), `ChargeLifecycleIT` | Local: 46 unit + 4 IT green under `mvn -B -pl apps/psp-simulator -am verify` (it suite). Each rule branch asserted |
| 2 | Validation branches byte-shape (spec §5.1) | txid `^[A-Z0-9]{25}$` → amount>0 → expiresAt future → callbackUrl http(s) → duplicate 409 `txid_already_exists`; `ErrorResponse{code,message}` | `ChargesControllerTest` (10 branches) | Local: green ✅ |
| 3 | Payer bank rules (spec §5.3): unknown 404, expired 409 `charge_expired`, paid 409 `already_paid`, else paid 200 | `Charge.pay(now, endToEndId, eventId)` | `ChargePaymentsControllerTest` (5) + `ChargeTest` rule table | Local: green ✅ |
| 4 | Stable `endToEndId` (`^E[A-Za-z0-9]{31}$`) + stable `eventId` per payment (dedupe anchor, spec §5.4/D6) | `EndToEndIdGenerator` (E+31, SecureRandom), `EventIdGenerator` (`psp-evt-<uuid4>`), stamped by `Charge.pay` | `IdGeneratorTest` (formats + 100-sample property), `ChargeLifecycleIT`, `ChaosPaymentDuplicateIT` (same eventId across the 2 copies) | Local: green ✅ |
| 5 | HMAC-SHA256 signer matches the **shared test vector** (spec §5.4) | `WebhookSigner` — lowercase-hex over `timestamp + "." + rawBody`; independent second vector too | `WebhookSignerTest` (3) — spec vector asserted verbatim | Local: green ✅ (vector recomputed independently with python3 before writing the test) |
| 6 | Async single-attempt delivery; exact bytes signed = exact bytes sent (spec §5.4, §8) | `WebhookEvent.of(charge)` serialized once → `AsyncWebhookDispatcher` (pool 4, RestClient 2s/5s, WARN-on-failure, injected Clock) | `WebhookEventTest` (byte-exact JSON), `WebhookDispatcherTest` (recompute over captured bytes+timestamp == captured signature; single-attempt) | Local: green ✅ |
| 7 | Five chaos knobs wired, deterministic captured behavior, defaults all-off (spec §6, M0 contract) | `ChaosProperties` (bounds at binding) + dispatcher delay/drop/duplicate + `ChaosFilter` error/latency on `/cobs/**` only | `PspConfigBindingTest` (3), `ChaosFilterTest` (4), dispatcher forced-mode tests (duplicate/drop/delay) × 2 layers (direct + endpoint IT) | Local: green ✅ (46 + 4) |
| 8 | Signature procedure mirrored for E4 (receiver recomputes from captured bytes + timestamp) | `TestWebhookReceiver` stub in TEST scope captures raw bytes + headers; never in production | `ChargeLifecycleIT` does the §5.4 recompute end to end | Local: green ✅ |
| 9 | Scope discipline: simulator only, no platform imports, no new deps | pom carries web/actuator/starter-test only; no shared-module classes used | `scripts/check-boundaries.sh` + `git diff --stat main -- apps/api modules` = 0 lines | Local: `check-boundaries: OK` ✅ after every commit (S1–S7) |
| 10 | Docs synced | `docs/design.md` §12 gains `CHAOS_PSP_LATENCY_MS`/`CHAOS_SEED`; `docs/epics.md` E2 → ✅; CHANGELOG Unreleased; lessons #13 (Jackson 3) | review | ✅ this matrix row |

## Declared deviations (residual, with owner)

| Deviation | Why | Owner | Target |
|---|---|---|---|
| No `@WebMvcTest`: slice tests boot the full app context (`@SpringBootTest` + Spring `MockMvc` via `webAppContextSetup`) | Boot 4.1.1 `spring-boot-test-autoconfigure` ships no web slice (only json/jdbc); zero new dependencies | — | permanent (documented, backlog Decisions) |
| Stub receiver = test-local `@RestController` (`TestWebhookReceiver`), not WireMock | spec §7 latitude; test-scope, component-scanned, captured raw bytes + headers are identical to what E4 will see | — | E4 reuses the assertion shape |
| Jackson 3 packages (`tools.jackson.*`) used, not `com.fasterxml.*` | Boot 4.1.1 resolves Jackson 3 databind (`tools.jackson.core:jackson-databind`); `com.fasterxml.databind` does not exist on the classpath | — | permanent (lesson #13) |
| `docker/.env.example` not updated with `CHAOS_*` | out of epic scope (only `apps/psp-simulator` + tasks/docs); M0 contract kept intact | — | open follow-up before E4 compose wiring — E4 |
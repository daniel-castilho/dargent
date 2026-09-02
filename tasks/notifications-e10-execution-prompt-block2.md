# Execution Prompt — E10 Block 2: S6 read API + S7 docs + M2 closure

**Issued:** 2026-09-02 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited: messages vs diffs, run pairs by number AND id, reds ALWAYS cited, greps at the cited commit).
**Prerequisite:** Block 1.5 closed green (BD-17/TD-16/BD-18 zeroed; S1–S5 green in CI #113–#116; head
`f6212b7`). **Push is the owner's action.**

**Adjudicated before execution (owner decision 2026-09-02):** spec §7 required a `merchantId` query param,
which conflicts with AGENTS §3.7 (tenant comes from the credential — never from path, query or body).
**Resolution: tenant comes from the principal.** S6 drops the required `merchantId` query param, scopes all
reads to the authenticated principal's merchant, and does not emit `merchant_id` from the body. The spec
§7 line is amended to match. This is recorded as a stop-and-report divergence resolution, not a silent fix.

---

## Source of truth (binding)

`tasks/notifications-e10-spec.md` §5 (read side) + §7 (read API) as amended below · §8.3 `NotificationsApiIT`
(must exist, name locked) · `modules/notifications` hexagonal layout (domain/application/adapter) ·
AGENTS §2/§3.7/§4.1 · ledger/payments read patterns as the shape template.

### Spec §7 (amended 2026-09-02 — tenant from principal)

`GET /v1/notifications` — API-key auth reused from payments (same filter/interceptor scope; route declared
in SecurityConfig, AGENTS §4.1).

- Query: `type` (optional), `limit` (optional, default 20, max 100), `cursor` (optional, opaque keyset
  token over `(created_at DESC, id DESC)`).
- Tenant/merchant comes from the authenticated principal (AGENTS §3.7); the merchant is never taken from
  path, query or body. Cross-merchant returns 404-style empty (existing auth behavior).
- 200 → `{ "data": [ { "id", "event_id", "type", "txid", "occurred_at", "created_at" } ], "next_cursor": string|null }`.
- 400 invalid/missing params (bad `limit`, malformed cursor) · 401/403 per existing auth behavior.
- `payload` is NOT returned (lean list; detail endpoint is stretch — out of scope).

## S6 — read API

1. **Contract tests first** — `apps/api/src/test/java/io/dargent/api/notifications/NotificationsApiIT.java`
   (name locked): seeded rows → GET shaped 200; pagination walk (cursor round-trip); 400 on bad params;
   auth negative (401 without key). Mirror the ledger/payments IT harness (Testcontainers + LocalStack).
2. **Read port + adapter** in `modules/notifications`: add a read method/query class to the store mirroring
   the ledger/payments read shape; keyset predicate `(created_at, id) < (?, ?)` ORDER BY `created_at DESC,
   id DESC`. No `payload` in the projection.
3. **Controller** in `apps/api` (`/v1/notifications`) mirroring `PaymentController.list` (opaque cursor
   decode once; clamp limit 1..100); route declared in `SecurityConfig` (AGENTS §4.1); merchant from
   `ApiKeyPrincipal`.
4. Zero Spring annotations in `modules/notifications/src/main`; no `payload` exposure; no new env names.

## S7 — docs + flip + M2 closure

1. README / CHANGELOG / `docs/epics.md` present-tense for E10 notifications (S7 rule). Flip the E10 epic.
2. `tasks/notifications-e10-spec.md` §10 matrix: fill S6/S7 rows with real pairs; status ✅ where proven.
3. Acceptance matrix row for the read API milestone item.

## Discipline

TDD (contract tests first); each commit pushed green before the next; run pairs cited number AND id;
no `@Disabled`, no skips, no `Thread.sleep` (Awaitility/long-polls only); env names untouched; commit
message = diff (pre-push hunk check); correction/grep output pasted with the commit SHA in the handoff.

## Scope & stop conditions

- Allowed: `modules/notifications` (read port/adapter), `apps/api` (controller + SecurityConfig route +
  ITs), `tasks/` + `docs/` + README/CHANGELOG. Zero lines in `modules/payments`, `modules/ledger`,
  `apps/psp-simulator`.
- STOP-AND-REPORT on: any red you cannot explain in writing; any felt need to keep a test disabled or
  bypass the main bean graph; any new env name; any schema/env change; any divergence from the amended §7.

## Handoff report

Commit shas + messages; run pairs (number AND id) including reds; greps with their commit id; exact head
sha at handoff time. Then stop. On verified evidence: S6/S7 green; E10 closed; M2 closed.

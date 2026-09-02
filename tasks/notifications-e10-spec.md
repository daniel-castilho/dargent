# E10 Spec — Notifications Consumer (exact contracts)

Every contract below is binding. Deviation = stop-and-report (P2). "Ledger mirror" means: copy
the shape of the named ledger artifact, adapted, not imported (module isolation).

## §1 Module

- Path `modules/notifications`, package root `io.dargent.notifications`.
- Layers mirror ledger: `application/` (use case, reader), `domain/port/out/` (store port),
  `adapter/out/db/` (JDBC), `adapter/out/messaging/` (SQS consumer).
- If the hollow module already contains scaffolding (pom, architecture test, schema dir): LIST IT
  FIRST and extend it; do not duplicate or overwrite. Divergent scaffolding → P5.

## §2 Migration (per-module Flyway)

- Location: `modules/notifications/src/main/resources/db/migration/`. If the module's history is
  empty, the first migration is `V101__notifications_schema.sql`; if a history exists, take the
  next free number. Forward-only, expand-only.
- If the module must register its own Flyway bean, mirror ledger's bean configuration exactly.

### §2.1 DDL

```sql
CREATE SCHEMA IF NOT EXISTS notifications;

CREATE TABLE IF NOT EXISTS notifications.notification (
    id           UUID        PRIMARY KEY,
    event_id     UUID        NOT NULL,
    type         TEXT        NOT NULL,
    txid         TEXT        NULL,
    merchant_id  UUID        NOT NULL,
    payload      JSONB       NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_merchant_created
    ON notifications.notification (merchant_id, created_at DESC, id DESC);
```

`payload` = the envelope's payload JSON (already validated by the reader). The index is the
keyset-cursor backing index for §7.

## §3 Reader (BD-16 shape is the template)

`EventEnvelopeReader` mirroring `io.dargent.ledger.application.EventEnvelopeReader` **as of
`e946a15`** (post-BD-16): `tools.jackson.databind` only; ONE shared field mapper; default ctor +
injecting ctor; ALL parse failures inside `read()` leave as `IllegalArgumentException`
(timestamp parse wrapped: `DateTimeException` → IAE); reuses `io.dargent.shared.events.EventEnvelope`.

## §4 Use case

`NotificationIngestionUseCase.processMessage(String rawBody) -> boolean`:

| Case | Behavior |
|---|---|
| Envelope unreadable (any IAE from reader) | return **false** (nack → DLQ path). No row. |
| Envelope ok, `insertNotificationIfAbsent` inserted | return **true** (ack). One row written. |
| Envelope ok, duplicate `event_id` (insert false) | return **true** (ack-skip). **Zero writes.** |

- Every event type records. No branching, no filtering, no enrichment in E10.
- Row: new UUID id; `event_id`, `type` from envelope; `txid` = `aggregateId`; `merchant_id`
  from envelope; `payload` = envelope payload JSON; `occurred_at` from envelope.
- No transaction beyond the single insert (one statement — the dedupe constraint is the guard).

## §5 Store port + adapter

`NotificationStore` (domain port), `JdbcNotificationStore` (adapter, `JdbcClient`):

```sql
INSERT INTO notifications.notification
    (id, event_id, type, txid, merchant_id, payload, occurred_at)
VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
ON CONFLICT (event_id) DO NOTHING
```

Read side (S6) lives in the adapter as a port method or query class mirroring ledger's read
patterns; keyset predicate: `(created_at, id) < (?, ?)` ORDER BY `created_at DESC, id DESC`.

## §6 Consumer + wiring

`SqsNotificationConsumer` in `adapter/out/messaging/` mirroring `SqsEventConsumer` exactly:
`runOnce()` → receive (max `DARGENT_NOTIFS_BATCH`, wait time `DARGENT_NOTIFS_POLL_MS` seconds
semantics as ledger) → per message `processMessage` → collect acks → one `deleteMessageBatch`
for the acked only. Hosted in `apps/api` behind the gate, wired like the ledger consumer.

### §4.1 Environment contract (new names — complete list, defaults are part of the contract)

| Name | Default | Meaning |
|---|---|---|
| `DARGENT_NOTIFS_CONSUMER_ENABLED` | `false` | consumer on/off (opt-in, like ledger) |
| `DARGENT_NOTIFS_QUEUE_URL` | — | notify queue URL (required when enabled) |
| `DARGENT_NOTIFS_POLL_MS` | `1000` | long-poll wait |
| `DARGENT_NOTIFS_BATCH` | `10` | max messages per receive |

No other new env names in E10. Existing contract names are never touched.

## §7 Read API (S6)

`GET /v1/notifications` — API-key auth reused from payments (same filter/interceptor scope).

- Query: `type` (optional), `limit` (optional, default 20, max 100), `cursor` (optional, opaque keyset
  token over `(created_at DESC, id DESC)`).
- **Tenant comes from the authenticated principal (AGENTS §3.7).** The merchant is never taken from path,
  query or body. Cross-merchant access answers 404-style empty, not 403.
  (Amended 2026-09-02: the original draft required a `merchantId` query param; that contradicts AGENTS §3.7
  and was adjudicated tenant-from-principal — see block 2 prompt.)
- 200 → `{ "data": [ { "id", "eventId", "type", "txid", "occurredAt", "createdAt" } ], "nextCursor": string|null }`.
  (Amended 2026-09-02: response field names changed from snake_case to camelCase to match the Payments API
  serialization convention — owner decision, stop-and-report; DB columns stay snake_case.)
- 400 invalid params (bad `limit`, malformed cursor) · 401/403 per the existing auth behavior.
- `payload` is NOT returned in the list (keep the endpoint lean; detail endpoint is stretch).

## §8 Integration tests (names locked)

In `apps/api/src/test/java/io/dargent/api/notifications/`, ledger harness:

1. `NotificationLoopIT` — publish valid event to notify queue → `runOnce` → row exists, ack;
   same event again → still one row, zero new writes.
2. `NotificationPoisonDlqIT` — malformed body → not acked; after maxReceive attempts the message
   lands in the notify DLQ (mirror ledger's poison IT mechanics and assert discipline).
3. `NotificationsApiIT` — seeded rows → GET shaped 200; pagination walk (cursor round-trip);
   400 on bad params; auth negative.

## §9 Hygiene gates (permanent, from the first commit)

| Gate | Expectation |
|---|---|
| `grep -rn "com.fasterxml" modules/notifications/src/main` | 0 hits |
| `grep -rln "software.amazon.awssdk" modules/notifications/src/main` | only the SQS consumer |
| `grep -rn "@Component\|@Service\|@Repository\|@Autowired\|@Value\|org.springframework.beans\|org.springframework.stereotype" modules/notifications/src/main` | 0 hits |
| `grep -rn "Thread.sleep" modules/notifications/src/test apps/api/src/test/java/io/dargent/api/notifications` | 0 hits |
| `git log --oneline -- modules/payments/src/main modules/ledger/src/main` (since block-1 start) | only the sanctioned rider 0b touches ledger TEST sources; zero prod touches |

Greps are pasted in the handoff with the commit id they ran at.

## §10 Acceptance matrix (to be appended to by the executor)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 TD-15 | README honesty pass | diff + audit | pair | ◻ |
| S0 BD-15R | guard IT trigger leg | IT name + pair | pair | ◻ |
| S0 matrix nit | citation pair completed | diff | pair | ◻ |
| S1 | migration applies | `NotificationLoopIT` green | pair | ◻ |
| S2 | reader poison-by-contract | `EventEnvelopeReaderTest` (module) | pair | ◻ |
| S3 | use case matrix | `NotificationIngestionUseCaseTest` | pair | ◻ |
| S4 | consumer translation | `SqsNotificationConsumerTest` | pair | ◻ |
| S5 | loop + poison ITs | `NotificationLoopIT` + `NotificationPoisonDlqIT` (§8 names) | #113 `33674334484` / #114 `33675295464` | ◻ |
| S6 | API contract | `NotificationsApiIT` | pair | ◻ |
| S7 | docs + flip + M2 ✅ | citation commit pair | pair | ◻ |

# E10 Backlog — Notifications Consumer (closes M2)

Molde: Spotpobre P0. Story map in execution order; each story lists its acceptance criteria.
Numbers are milestones for sequencing, not estimates.

## Story map

```
E10 Notifications
├── S0  Riders (sanctioned debt from the BD-15/16 block)          [Block 1, step 0]
├── S1  Module scaffold + migration (notifications schema)        [Block 1]
├── S2  Envelope reader (Jackson 3, BD-16 shape) + unit proofs    [Block 1]
├── S3  Ingestion use case + store port + JDBC adapter            [Block 1]
├── S4  SQS notification consumer + app wiring + env contract     [Block 1]
├── S5  Integration tests (loop / dedupe / poison-DLQ)            [Block 1]
├── S6  Read API: GET /v1/notifications (cursor pagination)       [Block 2]
├── S7  Docs + hygiene matrix + flip + M2 closure                 [Block 2]
└── Stretch (NOT in E10)                                          — out of scope
```

## Stories

### S0 — Riders (docs/test-only; the only sanctioned touches outside the module)
- TD-15: README honesty pass — (i) reconciliation sentence recast (E2's drop proof is real;
  recovery lands with E5, stated as future), (ii) subtitle scoped as architecture analogy,
  (iii) money-flow block marked target-state, (iv) stale E4 comment fixed; every README claim
  cross-checked against the Current-state table.
- BD-15R: failure-injection leg added to the existing BD-15 guard IT (trigger mechanics exactly
  as adjudicated in the fix-block prompt Q1/Q2).
- e7 matrix: the race row's dangling citation pair completed.
- **Accept:** 3 commits, each green on push; register items TD-15/BD-15R close on audit.

### S1 — Scaffold + migration
- `modules/notifications` Maven module (mirror ledger's pom: JdbcClient, tx, sqs SDK, jackson 3 —
  disclosed). Per-module Flyway; table `notifications.notification` per spec §3.1.
- **Accept:** `mvn -pl modules/notifications test` green; migration forward-only, expand-only;
  module compiles with zero Spring annotations in main.

### S2 — Reader
- `EventEnvelopeReader` mirroring ledger's post-BD-16 shape: single `tools.jackson` field mapper,
  all parse failures (incl. timestamp) → `IllegalArgumentException`, default + injecting ctors.
- **Accept:** unit matrix green — valid envelope, malformed JSON, missing field, malformed
  `occurredAt` ("not-a-date" → poison by contract), wrong-type guard if ledger has one.

### S3 — Use case + store
- `NotificationIngestionUseCase.processMessage(raw)`: read envelope (poison → false) →
  `insertNotificationIfAbsent` (UNIQUE on event_id) → inserted → ack; duplicate → ack-skip zero
  writes. Every event type records (no branching in E10).
- Port `NotificationStore` + `JdbcNotificationStore` (`ON CONFLICT (event_id) DO NOTHING`).
- **Accept:** unit matrix on fakes — valid records once; duplicate ack-skips; poison returns
  false; balances of writes asserted.

### S4 — Consumer + wiring
- `SqsNotificationConsumer` mirroring `SqsEventConsumer`: runOnce, batch receive, binary ack
  (deleteMessageBatch only for true), long poll `DARGENT_NOTIFS_POLL_MS`, batch
  `DARGENT_NOTIFS_BATCH`, gate `DARGENT_NOTIFS_CONSUMER_ENABLED` (default false).
- Hosted in `apps/api` config exactly like the ledger consumer.
- **Accept:** consumer unit test (translation contract: false → never deleted; true → deleted
  with correct entries; mixed batch) — mirror of `SqsEventConsumerTest`.

### S5 — Integration tests (Testcontainers + LocalStack, ledger harness)
- `NotificationLoopIT`: publish event → runOnce → exactly one row, ack; redeliver same event →
  still one row (dedupe), zero writes.
- `NotificationPoisonDlqIT`: malformed body survives maxReceive attempts → notify DLQ (mirror
  ledger's poison IT mechanics incl. its assert discipline).
- **Accept:** both green in CI; no sleeps; names locked by spec §6.

### S6 — Read API
- `GET /v1/notifications?merchantId=&type=&cursor=&limit=` — API-key auth reused; keyset cursor;
  Stripe-shape `{data, next_cursor}`; 400 invalid params; 401/403 auth.
- **Accept:** E2E IT (seeded rows → shaped list → pagination walk → auth negatives) green.

### S7 — Docs + flip
- README: notifications appear in Current-state + money-flow as DONE only when done (§10 rule).
- CHANGELOG entry; `docs/epics.md` E10 row ✅; M2 row flips ✅ with the closure citation.
- Hygiene matrix section for E10 (greps: com.fasterxml, AWS confinement, no-Spring-in-main,
  no Thread.sleep) pasted with commit ids.
- **Accept:** flip = last content commit + exactly one citation commit; run of the citation
  commit is not registered (#57/#67 precedent).

### Stretch — explicitly OUT of E10
Delivery channels (email/push), notification templates, user preferences, read models beyond
the single list endpoint. None of these may appear in code, docs, or commit messages.

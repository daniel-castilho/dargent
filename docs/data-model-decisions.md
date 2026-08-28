# Data Model Decisions

Human decisions about the data model, recorded while they are made. Format: context → decision →
alternatives considered → consequences. When intuition says "obvious", write it down anyway —
obviousness expires and is expensive to reconstruct.

---

## D1. PostgreSQL 16 as the single source of truth

- **Context:** LocalStack carries the messaging but must not carry durable state (its Community edition is
  in-memory; reference projects built entire snapshot/restore machines around emulated databases — we refuse that cost).
- **Decision:** PostgreSQL 16 stores all truth (payments, ledger, outbox). LocalStack is disposable; event
  replay exists via outbox republish.
- **Alternatives:** PostgreSQL 17/18 (no capability we need; 16 is boring and EOL 11/2028); DynamoDB on
  LocalStack (rejected: needs lock/outbox/relational semantics we'd fight for).
- **Consequences:** `pg_dump` + tested restore drill is the entire durability story; queues can be wiped at will.

## D2. Schema-per-module, zero cross-schema FK or JOIN

- **Context:** modular monolith whose modules must be extractable without refactoring.
- **Decision:** `payments`, `ledger`, `notifications` are separate Postgres schemas; a module's Flyway location
  owns its schema; no cross-schema foreign keys or joins, enforced by review and boundary script.
- **Alternatives:** single schema with table prefixes (cheaper, but extraction breaks silently); separate
  databases per module (real isolation, heavier ops for v1).
- **Consequences:** extracting a module = move consumer to another JVM; some "easy" cross-module reporting
  queries must be served by events instead — by design.

## D3. UUIDv7 generated in the application

- **Context:** primary keys need uniqueness across future service split; B-tree locality matters.
- **Decision:** UUIDv7 (time-ordered) generated in the application layer, `uuid` columns in Postgres.
- **Alternatives:** DB-generated `bigserial` (leaks business volume, splits badly); UUIDv4 (index churn).
- **Consequences:** ids sort by creation time; no sequence dependencies; `id` exists before insert (useful for outbox rows).

## D4. `txid` is a 25-character alphanumeric public id

- **Context:** PIX caps `txid` at 25 alphanumeric chars. ULID is 26 — one char too many. This is a real,
  delightful constraint.
- **Decision:** random alphanumeric (cryptographically secure RNG), uniqueness by `unique` constraint with
  bounded retry on collision; `txid` is the public API id and the SNS `MessageGroupId` (ordering key).
- **Alternatives:** UUID-derived truncation (collisions on 19 hex chars are fine but ugly in URLs/invoices);
  numeric sequence (enumerable, leaks volume).
- **Consequences:** collision retry is in the generator's contract; `txid` appears in logs and QR payloads.

## D5. Money as `bigint` cents; fees in basis points

- **Context:** float money is a classic defect class; `numeric` invites precision drift across layers.
- **Decision:** `amount_cents bigint` everywhere; fee math in `long` with basis points; rounding of the fee is
  **down** (merchant-favorable on net) and property-tested (`fee + net == amount`).
- **Alternatives:** `numeric(12,2)` (readable, drifts); minor-unit classes per currency (over-engineering for BRL-only v1).
- **Consequences:** reporting UIs must format cents; integrations must send integer cents (contract-level validation).

## D6. State transitions = conditional UPDATE + version column

- **Context:** expiration scheduler, webhook intake, and resurrection can race on the same payment.
- **Decision:** every transition is `UPDATE payments SET status=:to, version=version+1 WHERE id=:id AND
  status IN (:allowed) AND version=:v`; zero rows ⇒ lost race ⇒ re-read and decide. The entity's domain guard is
  the first line; the conditional update is the last.
- **Alternatives:** pessimistic lock on every transition (unnecessary contention); application-only checks (races).
- **Consequences:** lost-race handling is a first-class code path in every transition use case, covered by concurrent ITs.

## D7. `idempotency_keys` stores the full response snapshot

- **Context:** retries must return the original response byte-for-byte, including after success; concurrent
  retries must not block the first request.
- **Decision:** table `idempotency_keys(key unique, request_fingerprint, response_snapshot jsonb, payment_id,
  state IN_FLIGHT|COMPLETED)`; same key + different fingerprint ⇒ 409; same key in flight ⇒ 425 + Retry-After;
  cleanup job after 24h.
- **Alternatives:** Redis-only idempotency (cache is not truth); blocking concurrent retries (holds threads, complexity).
- **Consequences:** responses are deterministic and storable as JSONB; snapshot format is part of the contract.

## D8. `webhook_events` keeps the raw payload, immutable

- **Context:** parsing bugs and new event types must not lose information; attack forensics need originals.
- **Decision:** `webhook_events(provider_event_id unique, payload_raw jsonb immutable, status
  RECEIVED|PROCESSED|IGNORED, signature_valid bool, received_at)`. Raw persisted **even on invalid signature**.
- **Alternatives:** parse-and-discard (loses replay and forensics); file storage (indirection with no gain).
- **Consequences:** reprocessing/replay tools read from this table; schema evolution cannot corrupt history.

## D9. Outbox rows carry the full delivery lifecycle

- **Context:** at-least-once publishing needs retry semantics, exhaustion and operator requeue.
- **Decision:** `outbox(id, aggregate_id, type, version, payload jsonb, request_id, status
  PENDING|SENT|FAILED|EXHAUSTED, attempt_count, next_attempt_at, published_at)`; relay claims with
  `FOR UPDATE SKIP LOCKED`; backoff 30s→2min→5min; `EXHAUSTED` stops polling; audited requeue endpoint resets to `PENDING`.
- **Alternatives:** Debezium CDC (heavy ops for one app); Spring Modulith event registry (couples design to framework);
  delete-after-publish (loses audit + replay).
- **Consequences:** outbox doubles as the replay source; `published_at` gaps are the duplicate-publish window (accepted, consumers idempotent).

## D10. Ledger is append-only; balances are a transational projection

- **Context:** correctness demands an immutable journal; merchant-facing reads and refund checks need O(1) balances.
- **Decision:** `journal_entries` + `ledger_entries` with no UPDATE/DELETE (grants enforce it); `balances`
  projection updated in the same transaction as the insert; a daily proof job compares projection vs `SUM(lines)`
  and fails loudly on drift.
- **Alternatives:** on-the-fly `SUM` (correct, but O(n) per read and no locking anchor); event-sourced rebuilds (over-kill).
- **Consequences:** the projection is authoritative for locking (`available ≥ refund` checks) but never for truth;
  jqwik property tests pin `projection == SUM` under random event sequences.

## D11. Accounts are a fixed minimal chart

- **Context:** we are a mini-PSP; money moves PSP clearing → merchant pending → merchant available → (refunds out), fees skimmed.
- **Decision:** `ASSET:PSP_CLEARING`, `LIABILITY:MERCHANT:{id}:PENDING`, `LIABILITY:MERCHANT:{id}:AVAILABLE`,
  `REVENUE:PLATFORM_FEES` — nothing else in v1 (no payouts).
- **Alternatives:** full double-entry chart with settlement accounts per bank (deferred until payouts exist).
- **Consequences:** D+1 settlement moves pending→available; refund drains available (+fee reversal); balance
  report is 4 rows per merchant.

## D12. Indexes are partial where states are skewed

- **Context:** schedulers scan tiny slices of large tables (`PENDING` and expired; unpublished outbox).
- **Decision:** partial indexes: `payments (expires_at) WHERE status='PENDING'`;
  `outbox (next_attempt_at) WHERE status='PENDING'`; listing index `(merchant_id, created_at DESC, txid DESC)`.
- **Alternatives:** plain composite indexes (wasted space, planner fine but larger).
- **Consequences:** scheduler scans stay O(result set); index predicates are documented here and must be kept in
  sync with state enums — covered by an ArchUnit-free checklist in migration review.

## D13. Migrations are forward-only with expand/contract

- **Context:** blue-green means release N and N+1 run concurrently against one schema.
- **Decision:** no rollback scripts; every migration is written to keep both releases working; destructive
  changes split across two releases.
- **Alternatives:** rollback scripts (untested in practice, rot); deploy-window locks (downtime by another name).
- **Consequences:** migration review includes the two-release checklist; the restore drill validates that
  schema + backup history stay coherent.

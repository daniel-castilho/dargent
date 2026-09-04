# E9 Backlog — Delivery Hardening (closes M3)

Molde: Spotpobre P0. E9 flips its row AND M3 (with the E5/E8 chain cited).

## Story map

```
E9 Delivery Hardening
├── S1  Exhaustion contract (PENDING→EXHAUSTED at maxAttempts)         [Block 1]
├── S2  Audited requeue endpoint (EXHAUSTED→PENDING, admin-gated)      [Block 1]
├── S3  Republish tool (window replay, new event_ids)                  [Block 1]
├── S4  Scenario 20 proof: republish ⇒ no double-journaling            [Block 1]
├── S5  DLQ inspection recipes (docs + query proofs)                   [Block 2]
├── S6  Docs honesty pass + matrix + E9 flip + M3 flip + citation      [Block 2]
└── (scenario 18 poison→DLQ already proven — E9 only documents it)
```

## Stories

### S1 — Exhaustion contract
- `OutboxDeliveryUseCase`: on publish failure, `attemptCount+1 ≥ maxAttempts` →
  `markExhausted(id, attemptCount)` (conditional `WHERE status='PENDING'`) instead of another
  backoff; EXHAUSTED rows never re-enter `claimPending` (status filter). `maxAttempts` default **3**
  (`DARGENT_RELAY_MAX_ATTEMPTS`, §4.1) — the ladder (30s/2m/5m) runs UNCHANGED before it.
- **Accept:** unit matrix (attempt 1→backoff, 2→backoff, 3→EXHAUSTED; EXHAUSTED never claimed;
  lost-race safe) + `OutboxExhaustionIT` (forced publisher failure ×3 → EXHAUSTED, unscheduled,
  audit-free-but-loggable; then scenario 19's requeue leg hands off to S2).

### S2 — Audited requeue endpoint
- `POST /v1/outbox/{id}/requeue`: conditional `EXHAUSTED→PENDING` + `attempt_count=0` +
  `next_attempt_at=now()`; 0 rows → 409 `not_exhaustible` (or 404 unknown id); success → 200 with
  the row representation; audit `outbox_requeued` with the API-key actor (real actor, NOT the
  sentinel — this is a human admin action).
- Auth: admin-gated. If the current key model has no admin scope, implement the minimum honest
  guard (separate key value via env, §4.1) and stop-and-report if you feel a roles system brewing.
- **Accept:** `OutboxRequeueIT` — requeue→relay publishes→SENT (scenario 19 END-TO-END);
  double-requeue idempotent; PENDING-row requeue rejected; auth negatives.

### S3 — Republish tool
- `POST /v1/outbox/republish` `{ "from": iso, "to": iso, "types": [..]? }` → for each SENT row in
  the window: INSERT a NEW PENDING row (new id, NEW event_id per §5 rule, same payload/aggregate)
  bounded by a max-batch; returns counts. Audit `outbox_republished` (window, count, actor).
- **Accept:** `OutboxRepublishIT` — window replay inserts bounded new rows; originals stay SENT;
  empty window → zero rows, 200.

### S4 — Scenario 20 proof (no double-journaling)
- Extend `RefundFlowIT`-style harness: confirm → journal (1 row) → republish the confirmed event's
  window → SECOND delivery consumed → journal STILL 1 row (event_id dedupe), notifications still 1,
  balances unchanged.
- **Accept:** scenario 20 leg green in CI.

### S5 — DLQ inspection recipes (Block 2, docs + query proofs)
- `docs/runbooks/dlq-inspection.md`: for ledger-events DLQ and notify DLQ — count query, peek
  (messageId/body/ReceiveCount), redrive decision tree, and "when is a DLQ row an incident vs
  expected" (poison vs transient). Each recipe proven by a query against the E6/E10 IT topology
  (screenshot-level evidence in the doc, or a tiny proof-IT if cheaper than staging evidence).
- **Accept:** doc lands with runnable queries; playbook §6 cross-linked.

### S6 — Docs + flips (Block 2)
- README: delivery-guarantee line gains backoff/exhaustion/requeue (present tense WITH proof);
  money-flow adds the exhausted→requeue detour; CHANGELOG; design.md deltas (maxAttempts + republish);
  AGENTS §8 sweep (DEBT-6 renumber rider from TD-24 lands here if still open); matrix §10 filled.
- **Docs-honesty riders (owner-approved 2026-09-03, from 3rd external analysis triage):**
  (a) **TD-26** — README guarantee-table rows claiming "shutdown-under-load gate in CI" and
  "security gates in CI" + stack rows `Micrometer + Prometheus` / `blue-green with canary` +
  `slos.md` metric "Source of truth" column: annotate each non-live mechanism with an explicit
  `(M4)`/`(M5)` marker (same treatment as TD-22). Present tense only WITH proof — never delete a claim.
  (b) **Refund × PSP limitation note** — one honest line in design.md + CHANGELOG: refund is an
  internal fact + `refund.created` outbox event; a PSP devolution rail does not exist in v1.0.0
  (post-v1.0.0 epic candidate). No code change in E9.
- **Explicitly out of E9:** DEBT-7 consolidation (`postJournal` × `postJournalWithoutBalances`
  duplicated SQL) — M4 refactor window with its own IT evidence. Do not touch audited money SQL here.
- **Accept:** flip = E9 ✅ + **M3 ✅** (same change set, chain cited) + exactly one citation commit
  (run unregistered, #57/#67). The milestone table row M3 turns ✅ with the three-epic chain.

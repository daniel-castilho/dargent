# DLQ Recipes (E9 S5)

This document provides operational runbooks for inspecting and recovering messages in Dead Letter Queues (DLQs).
All queues are FIFO with `maxReceiveCount=5`; after 5 failed receives a message is redriven to its DLQ.

## Queue Topology

| Consumer | Main Queue | DLQ | Redrive Policy |
|----------|------------|-----|----------------|
| Outbox Relay (payments.notify) | `dargent-payments-notify.fifo` | `dargent-payments-notify-dlq.fifo` | 5 receives |
| Ledger | `dargent-ledger-events.fifo` | `dargent-ledger-events-dlq.fifo` | 5 receives |
| Notifications | `dargent-notifications.fifo` | `dargent-notifications-dlq.fifo` | 5 receives |

## 1. Inspect DLQ Depth (Gauge)

```bash
# All DLQs
aws sqs get-queue-attributes \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/dargent-payments-notify-dlq.fifo \
  --attribute-names ApproximateNumberOfMessages

aws sqs get-queue-attributes \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/dargent-ledger-events-dlq.fifo \
  --attribute-names ApproximateNumberOfMessages

aws sqs get-queue-attributes \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/dargent-notifications-dlq.fifo \
  --attribute-names ApproximateNumberOfMessages
```

Prometheus metric: `dargent_dlq_messages{queue="..."}` (see `observability.md` §3).

## 2. Peek Messages Without Consuming

```bash
# Peek up to 10 messages (does NOT change visibility timeout / receive count)
aws sqs receive-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/dargent-ledger-events-dlq.fifo \
  --max-number-of-messages 10 \
  --wait-time-seconds 0 \
  --visibility-timeout 0 \
  --attribute-names All \
  --message-attribute-names All
```

## 3. Inspect a Poison Message Payload

```bash
# Receive one message (increments receive count)
aws sqs receive-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/dargent-ledger-events-dlq.fifo \
  --max-number-of-messages 1 \
  --wait-time-seconds 20 \
  --attribute-names All \
  --message-attribute-names All

# The body is the original event envelope JSON (payment.confirmed / refund.created)
# Key attributes to inspect:
# - MessageGroupId = aggregateId (txid)
# - MessageDeduplicationId = eventId
# - Subject = event type
# - ApproximateReceiveCount = 5 (max)
```

## 4. Common Failure Patterns & Remediation

### 4.1 Ledger: Invalid Payment Payload (REJECTED at boundary)
**Symptom**: `payment.confirmed` event with malformed payload (missing txid, negative amount, etc.)
**Diagnosis**:
```bash
# Extract payload from DLQ message
aws sqs receive-message --queue-url $DLQ_URL --max-number-of-messages 1 \
  --query 'Messages[0].Body' --output text | jq .
```
**Remediation**: Fix upstream payment creation; the event is idempotent by `eventId` — requeueing the *same* eventId will fail again. Fix the producer, then replay from outbox (E9 §4 republish) if the outbox row still exists.

### 4.2 Ledger: Insufficient Merchant Balance (refund.created → IGNORED)
**Symptom**: Refund event processed but merchant balance insufficient; event marked `IGNORED` with note `insufficient_merchant_balance`.
**Diagnosis**: Check `ledger.events` table:
```sql
SELECT event_id, type, status, note, payload
FROM ledger.events
WHERE status = 'IGNORED' AND note = 'insufficient_merchant_balance'
ORDER BY created_at DESC LIMIT 20;
```
**Remediation**: This is a business rule outcome, not a system error. The refund was correctly skipped. If merchant later gets funds, re-submit the refund.

### 4.3 Outbox Relay: Publish Timeout / SNS Error
**Symptom**: Outbox row stuck in `PENDING`/`EXHAUSTED`, DLQ has SNS publish failures.
**Diagnosis**: Check relay logs for `SnsEventPublisher` errors; verify SNS topic exists and permissions.
**Remediation**: Use E9 §3 requeue endpoint on `EXHAUSTED` rows after fixing SNS.

### 4.4 Notifications: Webhook Delivery Failure (5 retries exhausted)
**Symptom**: Notification event in DLQ after 5 failed HTTP deliveries to merchant webhook.
**Diagnosis**: Inspect message body for `webhook_url`, `attempt_count`, last error.
**Remediation**: Verify merchant webhook endpoint; if fixed, requeue from outbox (E9 §4 republish) to trigger new notification.

## 5. Requeue from DLQ (After Root Cause Fixed)

**⚠️ Never requeue blindly — fix the cause first.**

### Option A: Republish via Admin Endpoint (E9 §4) — Preferred
If the original outbox row is still `SENT` (not purged), republish it with a new salted `eventId`:
```bash
curl -X POST https://api.dargent.local/v1/outbox/republish \
  -H "Authorization: Bearer $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"from": "2027-01-01T00:00:00Z", "to": "2027-01-02T00:00:00Z", "types": ["payment.confirmed"]}'
```
This mints new PENDING rows with `{original}-r{n}` eventIds, which the relay will deliver fresh.

> **⚠️ Operational limitation (ratified E9 §4 / §6.4):** Republish mints new events that re-notify already-notified consumers. The ledger consumer dedupes by `eventId` (scenario 20), but the **notifications consumer does not dedupe** — it will re-deliver the webhook. Use republish only for events that genuinely failed delivery (DLQ), not for already-delivered events. Re-runs of the same republish are idempotent at both consumers (deterministic `{original}-r{n}` eventIds).

### Option B: Manual Requeue to Main Queue (If Outbox Row Gone)
```bash
# 1. Receive message from DLQ (delete it)
MSG=$(aws sqs receive-message --queue-url $DLQ_URL --max-number-of-messages 1 --output json)
BODY=$(echo "$MSG" | jq -r '.Messages[0].Body')
RECEIPT=$(echo "$MSG" | jq -r '.Messages[0].ReceiptHandle')
aws sqs delete-message --queue-url $DLQ_URL --receipt-handle $RECEIPT

# 2. Send to main queue (preserve FIFO ordering)
aws sqs send-message \
  --queue-url $MAIN_QUEUE_URL \
  --message-body "$BODY" \
  --message-group-id $(echo "$BODY" | jq -r '.aggregateId') \
  --message-deduplication-id "$(echo "$BODY" | jq -r '.eventId')-requeue-$(date +%s)"
```

## 6. Purge DLQ (After All Messages Inspected & Root Causes Fixed)

```bash
# Purge entire DLQ (irreversible)
aws sqs purge-queue --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/dargent-ledger-events-dlq.fifo
```

## 7. Runnable SQL Queries for Forensics

### 7.1 Ledger: Events in IGNORED/REJECTED state
```sql
SELECT e.event_id, e.type, e.status, e.note, e.txid, e.merchant_id, e.created_at
FROM ledger.events e
WHERE e.status IN ('IGNORED', 'REJECTED')
ORDER BY e.created_at DESC
LIMIT 50;
```

### 7.2 Ledger: Journal entries vs events (scenario 20 audit)
```sql
SELECT je.txid, je.event_id, e.status, e.note, je.created_at
FROM ledger.journal_entries je
JOIN ledger.events e ON e.event_id = je.event_id
WHERE je.txid IN (
  SELECT txid FROM ledger.journal_entries
  GROUP BY txid HAVING COUNT(*) > 1
)
ORDER BY je.txid, je.created_at;
```
Should return 0 rows (E9 S4 guarantees no double journaling per txid).

### 7.3 Outbox: Stale PENDING / EXHAUSTED rows
```sql
SELECT id, type, status, attempt_count, next_attempt_at, published_at
FROM payments.outbox
WHERE status IN ('PENDING', 'EXHAUSTED')
  AND next_attempt_at < NOW() - INTERVAL '1 hour'
ORDER BY next_attempt_at;
```

### 7.4 Audit trail for admin actions (requeue/republish)
```sql
SELECT command_name, actor_key_id, merchant_id, aggregate_id, created_at
FROM payments.audit_log
WHERE command_name IN ('outbox_requeued', 'outbox_republished')
ORDER BY created_at DESC
LIMIT 50;
```

## 8. Monitoring & Alerting

| Metric | Alert Threshold | Runbook |
|--------|----------------|---------|
| `dargent_dlq_messages{queue="dargent-ledger-events-dlq.fifo"}` | > 0 for 5m | §4.1-4.4 |
| `dargent_dlq_messages{queue="dargent-payments-notify-dlq.fifo"}` | > 0 for 5m | §4.3 |
| `dargent_dlq_messages{queue="dargent-notifications-dlq.fifo"}` | > 0 for 5m | §4.4 |

---

*Generated as part of E9 S5. Update after any queue topology change.*
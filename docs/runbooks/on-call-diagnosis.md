# On-Call Diagnosis Runbook (E11 S2)

Quick reference for operators at 03:00 — find a payment's status, last transition, and next retry in ≤2 min.

## Prerequisites

- Access to application logs (stdout / `docker logs`)
- `jq` installed for JSON parsing

## Drill Steps

### 1. Search by txid (primary path)

```bash
# Get the txid from merchant report or alert
TXID="DRc2256e74-5ca2-4ae2-ba0a"

# Search structured logs by txid
docker logs api-blue 2>&1 | jq -c "select(.txid == \"$TXID\")" | head -20
```

Expected output: JSON lines with `status`, `txid`, `request_id`, `next_reconcile_at` (or `next_attempt_at`), `merchant_id`.

### 2. Re-search by request_id (full trail)

```bash
# Extract request_id from step 1 output
REQ_ID="req-drill-01"

# Get full request trail
docker logs api-blue 2>&1 | jq -c "select(.request_id == \"$REQ_ID\")" | head -30
```

Expected output: Complete trail — intake, PSP call, relay, ledger ingest — all correlated by `request_id`.

### 3. Verify DB state (if logs rotated)

```bash
# Direct DB query (run in psql or via admin API)
SELECT status, next_reconcile_at, expires_at
FROM payments.payments
WHERE txid = 'DRc2256e74-5ca2-4ae2-ba0a';

SELECT type, request_id, created_at
FROM payments.outbox
WHERE aggregate_id = 'DRc2256e74-5ca2-4ae2-ba0a';
```

## Budget

- Target: ≤2 min operator time (modeled via injected Clock in IT)
- No wall-clock sleeps; all assertions via fixed Clock

## Escalation

- If txid not found in logs → check log retention / rotation
- If status = EXPIRED/FAILED → run reconciler manually or check PSP status
- If no outbox event → payment never created; check idempotency key
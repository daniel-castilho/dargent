package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.OutboxId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for relay-specific outbox operations (E6 §5.1).
 * Implemented by the same JDBC adapter that serves OutboxWriter — one adapter, one access path.
 */
public interface OutboxEventStore {

    /**
     * Claims up to {@code batch} PENDING rows whose {@code next_attempt_at <= now},
     * ordered by due date, using {@code FOR UPDATE SKIP LOCKED}.
     *
     * @param batch max rows to claim
     * @param now   time reference for due filter (injected Clock)
     * @return claimed rows (empty if none due)
     */
    List<OutboxRow> claimPending(int batch, Instant now);

    /**
     * Marks a row as SENT after successful publish.
     *
     * @param id              row id
     * @param attemptCount    new attempt count (previous + 1)
     * @param publishedAt     publication timestamp
     * @return true if row was updated (lost race if 0 rows)
     */
    boolean markSent(OutboxId id, int attemptCount, Instant publishedAt);

    /**
     * Marks a row as PENDING with incremented attempt count and next backoff time.
     *
     * @param id              row id
     * @param attemptCount    new attempt count (previous + 1)
     * @param nextAttemptAt   when the row becomes eligible again
     * @return true if row was updated (lost race if 0 rows)
     */
    boolean markFailed(OutboxId id, int attemptCount, Instant nextAttemptAt);

    /**
     * Marks a row as EXHAUSTED (E9 §2): the retry ceiling was reached.
     * Conditional on {@code status='PENDING'} so a lost race (another worker already advanced the
     * row) is a no-op — the caller re-reads and decides; EXHAUSTED rows never re-enter
     * {@link #claimPending}.
     *
     * @param id            row id
     * @param attemptCount  final attempt count (the app attempts the ladder before exhausting)
     * @return true if row was updated (lost race if 0 rows)
     */
    boolean markExhausted(OutboxId id, int attemptCount);

    /**
     * Requeues an EXHAUSTED outbox row back to PENDING (E9 §3): resets {@code attempt_count}=0 and
     * {@code next_attempt_at}=now so the relay claims it on the next poll. Human admin action —
     * the caller audits with the real API-key principal.
     *
     * @param id  outbox row id
     * @param now clock reference for {@code next_attempt_at}
     * @return the outcome; on {@code REQUEUED} also the row's payment {@code aggregateId} (the txid)
     *         so the audit can correlate against {@code payments.audit_log.aggregate_id varchar(25)}
     */
    RequeueResult requeueExhausted(OutboxId id, Instant now);

    /** Result of {@link #requeueExhausted}. */
    record RequeueResult(RequeueOutcome outcome, String aggregateId) {
        public static RequeueResult notFound() {
            return new RequeueResult(RequeueOutcome.NOT_FOUND, null);
        }

        public static RequeueResult notExhaustible() {
            return new RequeueResult(RequeueOutcome.NOT_EXHAUSTIBLE, null);
        }

        public static RequeueResult requeued(String aggregateId) {
            return new RequeueResult(RequeueOutcome.REQUEUED, aggregateId);
        }
    }

    /** Outcome of {@link #requeueExhausted}. */
    enum RequeueOutcome {
        REQUEUED, NOT_EXHAUSTIBLE, NOT_FOUND
    }

    /**
     * Purges old SENT rows for retention (E6 §5.4).
     *
     * @param cutoff   rows with {@code published_at < cutoff} are deleted
     * @param limit    max rows to delete per call
     * @return number of rows deleted
     */
    int purgeSent(Instant cutoff, int limit);

    /**
     * Outbox row data returned by {@link #claimPending}.
     */
    record OutboxRow(
            OutboxId id,
            String aggregateId,
            String type,
            int version,
            String payload,
            String requestId,
            int attemptCount
    ) {}
}
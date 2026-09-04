package io.dargent.payments.application;

import io.dargent.payments.domain.model.OutboxId;
import io.dargent.payments.domain.port.out.EventPublisher;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import io.dargent.payments.domain.port.out.OutboxEventStore.OutboxRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OutboxDeliveryUseCase (E6 §5.1): relay cycle for the transactional outbox.
 * <p>
 * One worker, one pass: claim → parse → publish → mark (conditional).
 * Runs inside a TransactionTemplate so claim+mark are atomic; publish runs inside the tx
 * (at-least-once semantics: crash after publish but before mark → duplicate, collapsed by FIFO dedup).
 * A row whose publish failures reach {@link Policy#maxAttempts()} (E9 §2) is marked EXHAUSTED
 * instead of scheduling another backoff; EXHAUSTED rows are never re-claimed.
 * Every {@link #PURGE_EVERY_CYCLES} cycles the SENT retention purge runs (spec §5.4) — the purge
 * and the relay touch disjoint statuses, so they never contend.
 */
public final class OutboxDeliveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryUseCase.class);

    /** Purge cadence (spec §5.4): every 60th cycle ≈ 1 min at default 1 s poll. */
    private static final int PURGE_EVERY_CYCLES = 60;
    /** Purge batch size (spec §5.4: LIMIT 1000). */
    private static final int PURGE_BATCH = 1000;

    private final OutboxEventStore store;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Policy policy;
    private final TransactionTemplate txTemplate;
    private int cyclesSincePurge;

    public OutboxDeliveryUseCase(OutboxEventStore store,
            EventPublisher publisher,
            ObjectMapper mapper,
            Clock clock,
            Policy policy,
            TransactionTemplate txTemplate) {
        this.store = store;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
        this.policy = policy;
        this.txTemplate = txTemplate;
    }

    /**
     * Runs one relay cycle for a single worker.
     *
     * @param batch max rows to claim this cycle
     * @return number of rows successfully published and marked SENT
     */
    public int runOnce(int batch) {
        maybePurge();
        return txTemplate.execute(status -> cycle(batch));
    }

    /**
     * Retention purge (spec §5.4): every {@code PURGE_EVERY_CYCLES} cycles, delete up to
     * {@code PURGE_BATCH} SENT rows older than the retention horizon. PENDING / FAILED /
     * EXHAUSTED rows are never purged by E6. One log line with rows deleted per run; a purge
     * failure never blocks the delivery cycle (next cycle retries).
     */
    private void maybePurge() {
        if (++cyclesSincePurge < PURGE_EVERY_CYCLES) {
            return;
        }
        cyclesSincePurge = 0;
        Instant cutoff = clock.instant().minus(Duration.ofDays(policy.retentionDays()));
        try {
            int deleted = store.purgeSent(cutoff, PURGE_BATCH);
            log.info("OUTBOX purge deleted={} cutoff={}", deleted, cutoff);
        } catch (Exception e) {
            log.error("OUTBOX purge failed cutoff={} error={}", cutoff, e.getMessage());
        }
    }

    private int cycle(int batch) {
        Instant now = clock.instant();
        List<OutboxEventStore.OutboxRow> claimed = store.claimPending(batch, now);
        if (claimed.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEventStore.OutboxRow row : claimed) {
            String eventId;
            try {
                eventId = extractEventId(row.payload());
            } catch (Exception e) {
                // Writer bug: row has no eventId — leave row PENDING, log error, move on
                log.error("OUTBOX row without eventId id={} type={} error={}", row.id(), row.type(), e.getMessage());
                continue;
            }

            try {
                publisher.publish(row.type(), row.payload(), eventId, row.aggregateId());
            } catch (Exception e) {
                int attempts = row.attemptCount() + 1;
                if (attempts >= policy.maxAttempts()) {
                    // Retry ceiling reached (E9 §2): EXHAUSTED, conditional — lost race → no-op.
                    boolean exhausted = store.markExhausted(row.id(), attempts);
                    log.error("OUTBOX publish failed id={} type={} attempts={} exhausted={} error={}",
                            row.id(), row.type(), attempts, exhausted, e.getMessage());
                } else {
                    // Schedule the next ladder attempt, leave PENDING
                    Instant nextAttempt = clock.instant().plus(backoff(attempts));
                    store.markFailed(row.id(), attempts, nextAttempt);
                    log.error("OUTBOX publish failed id={} type={} attempts={} next={} error={}",
                            row.id(), row.type(), attempts, nextAttempt, e.getMessage());
                }
                continue;
            }

            // Publish succeeded — mark SENT inside same transaction
            if (!store.markSent(row.id(), row.attemptCount() + 1, clock.instant())) {
                // Lost race: another worker marked it (or row was already processed)
                // The duplicate will be collapsed by FIFO dedup (eventId) on the consumer side
                log.warn("OUTBOX lost race marking SENT id={} type={}", row.id(), row.type());
                continue;
            }
            published++;
        }
        return published;
    }

    private String extractEventId(String payload) {
        // Strict Jackson parse using the injected mapper; missing/blank eventId = writer bug
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid payload JSON: " + e.getMessage(), e);
        }
        String eventId = node.path("eventId").asText(null);
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("missing or blank eventId");
        }
        return eventId;
    }

    private Duration backoff(int attempt) {
        // 1→30 s, 2→2 min, ≥3→5 min cap (spec §5.2)
        if (attempt <= 1) return Duration.ofSeconds(30);
        if (attempt == 2) return Duration.ofMinutes(2);
        return Duration.ofMinutes(5);
    }

    // ------------------------------------------------------------------ policy

    /**
     * Delivery policy parameters (derived from §5.7 BoE, not tuned).
     */
    public record Policy(
            int batchSize,          // DARGENT_RELAY_BATCH (default 32)
            int workers,            // DARGENT_RELAY_WORKERS (default 2)
            long pollMs,            // DARGENT_RELAY_POLL_MS (default 1000)
            int maxAttempts,        // DARGENT_RELAY_MAX_ATTEMPTS (E9 §4.1, default 3): ladder runs then EXHAUSTED
            Duration baseBackoff,   // 30 s (1st retry)
            Duration maxBackoff,    // 5 min (cap)
            int retentionDays       // DARGENT_OUTBOX_RETENTION_DAYS (default 7)
    ) {
        public static Policy fromEnv() {
            // Defaults per §5.7 BoE; maxAttempts default 3 per E9 §4.1
            return new Policy(32, 2, 1000, 3,
                    java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 7);
        }
    }
}
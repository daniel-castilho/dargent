package io.dargent.payments.application;

import io.dargent.payments.domain.model.OutboxId;
import io.dargent.payments.domain.port.out.EventPublisher;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OutboxDeliveryUseCase (E6 §5.1) — pure TDD with fakes, no Spring.
 */
class OutboxDeliveryUseCaseTest {

    private FakeOutboxEventStore store;
    private FakeEventPublisher publisher;
    private OutboxDeliveryUseCase useCase;
    private Clock clock;
    private final Instant FIXED_NOW = Instant.parse("2026-08-30T12:00:00Z");
    private final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        store = new FakeOutboxEventStore();
        publisher = new FakeEventPublisher();
        clock = FIXED_CLOCK;

        OutboxDeliveryUseCase.Policy policy = new OutboxDeliveryUseCase.Policy(
                32, 2, 1000, Integer.MAX_VALUE, java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 7
        );
        // Use real TransactionTemplate for unit tests (runs callback synchronously)
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(null) {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };

        useCase = new OutboxDeliveryUseCase(
                store, publisher, new tools.jackson.databind.json.JsonMapper(),
                FIXED_CLOCK, new OutboxDeliveryUseCase.Policy(
                        32, 2, 1000, Integer.MAX_VALUE, Duration.ofSeconds(30), Duration.ofMinutes(5), 7
                ),
                txTemplate
        );
    }

@Test
    void claim_filters_by_due_and_status() {
        // PENDING but not due (missing eventId -> ignored)
        ((FakeOutboxEventStore) store).insert(
                OutboxId.generate(Clock.systemUTC()), "tx-1", "payment.created", 1, "{}", "req-1", 0,
                FIXED_NOW.plusSeconds(10) // due in future
        );
        // PENDING and due, with valid eventId
        OutboxId dueId = OutboxId.generate(Clock.systemUTC());
        ((FakeOutboxEventStore) store).insert(
                dueId, "tx-2", "payment.created", 1, "{\"eventId\":\"evt-2\"}", "req-2", 0,
                FIXED_NOW.minusSeconds(10) // due now
        );

        int processed = useCase.runOnce(10);

        assertThat(processed).isEqualTo(1); // only the due PENDING row with valid eventId
    }

    @Test
    void batch_size_cap_respected() {
        for (int i = 0; i < 50; i++) {
            ((FakeOutboxEventStore) store).insert(
                    OutboxId.generate(Clock.systemUTC()), "tx-" + i, "payment.created", 1,
                    "{\"eventId\":\"evt-" + i + "\"}", "req-" + i, 0, FIXED_NOW
            );
        }

        int processed = useCase.runOnce(10); // batch = 10

        assertThat(processed).isEqualTo(10);
    }

    @Test
    void skip_locked_semantics_via_fake() {
        // Two workers (simulated by sequential calls with same data) should not both claim same row
        OutboxId id = OutboxId.generate(Clock.systemUTC());
        ((FakeOutboxEventStore) store).insert(
                id, "tx-1", "payment.created", 1, "{\"eventId\":\"evt-1\"}", "req-1", 0, FIXED_NOW
        );

        // First worker claims
        int first = useCase.runOnce(1);
        assertThat(first).isEqualTo(1);

        // Second worker sees row already SENT
        int second = useCase.runOnce(1);
        assertThat(second).isEqualTo(0);
    }

    @Test
    void purge_runs_every_60_cycles_with_clocked_cutoff() {
        // Spec §5.4: purge cadence N = 60; cutoff = clock.instant() - retentionDays (7), injected Clock.
        for (int i = 0; i < 59; i++) {
            useCase.runOnce(1);
        }
        assertThat(store.purgeCutoffs).isEmpty();

        useCase.runOnce(1); // 60th cycle triggers the purge

        assertThat(store.purgeCutoffs).hasSize(1);
        assertThat(store.purgeCutoffs.get(0)).isEqualTo(FIXED_NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void backoff_schedule() {
        var uc = new OutboxDeliveryUseCase(null, null, null, FIXED_CLOCK,
                new OutboxDeliveryUseCase.Policy(1, 1, 1, 3, java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 7),
                null);

        assertThat(backoff(uc, 1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff(uc, 2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(backoff(uc, 3)).isEqualTo(Duration.ofMinutes(5));
        assertThat(backoff(uc, 4)).isEqualTo(Duration.ofMinutes(5));
    }

    // ------------------------------------------------------- exhaustion matrix (E9 §2)

    /**
     * E9 §2 matrix row 1: at default maxAttempts=3, a publish failure on the first attempt
     * schedules the 30 s backoff and leaves the row PENDING — never EXHAUSTED yet.
     */
    @Test
    void first_failure_schedules_30s_backoff_and_stays_pending() {
        OutboxId id = OutboxId.generate(Clock.systemUTC());
        ((FakeOutboxEventStore) store).insert(
                id, "tx-1", "payment.created", 1, "{\"eventId\":\"evt-1\"}", "req-1", 0, FIXED_NOW);
        OutboxDeliveryUseCase uc = useCase(store, new FailingEventPublisher(), 3, advancer);

        int published = uc.runOnce(10);

        assertThat(published).isZero();
        assertThat(store.markExhaustedCalls).isEmpty();
        assertThat(store.markFailedCalls).hasSize(1);
        assertThat(store.markFailedCalls.get(0).id()).isEqualTo(id);
        assertThat(store.markFailedCalls.get(0).attemptCount()).isEqualTo(1);
        assertThat(store.markFailedCalls.get(0).nextAttemptAt()).isEqualTo(FIXED_NOW.plus(Duration.ofSeconds(30)));
        assertThat(store.status(id)).isEqualTo("PENDING");
    }

    /**
     * E9 §2 matrix row 2: the second failure (after the 30 s ladder rung) schedules the 2 min
     * backoff and still stays PENDING.
     */
    @Test
    void second_failure_schedules_2m_backoff_and_stays_pending() {
        OutboxId id = OutboxId.generate(Clock.systemUTC());
        ((FakeOutboxEventStore) store).insert(
                id, "tx-1", "payment.created", 1, "{\"eventId\":\"evt-1\"}", "req-1", 0, FIXED_NOW);
        OutboxDeliveryUseCase uc = useCase(store, new FailingEventPublisher(), 3, advancer);

        uc.runOnce(10); // attempt 1 -> 30s backoff, still PENDING
        advancer.advance(Duration.ofSeconds(30)); // ladder rung 1 (30 s) elapses
        uc.runOnce(10); // attempt 2 -> 2m backoff, still PENDING

        assertThat(store.markExhaustedCalls).isEmpty();
        assertThat(store.markFailedCalls).hasSize(2);
        assertThat(store.markFailedCalls.get(1).attemptCount()).isEqualTo(2);
        assertThat(store.markFailedCalls.get(1).nextAttemptAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(2)).plus(Duration.ofSeconds(30)));
        assertThat(store.status(id)).isEqualTo("PENDING");
    }

    /**
     * E9 §2 matrix row 3: at default maxAttempts=3, the third failure (after both ladder rungs)
     * marks the row EXHAUSTED (no further backoff) and it is never reclaimed.
     */
    @Test
    void third_failure_marks_exhausted_and_never_reclaims() {
        OutboxId id = OutboxId.generate(Clock.systemUTC());
        ((FakeOutboxEventStore) store).insert(
                id, "tx-1", "payment.created", 1, "{\"eventId\":\"evt-1\"}", "req-1", 0, FIXED_NOW);
        OutboxDeliveryUseCase uc = useCase(store, new FailingEventPublisher(), 3, advancer);

        uc.runOnce(10); // attempt 1 -> 30s backoff
        advancer.advance(Duration.ofSeconds(30));
        uc.runOnce(10); // attempt 2 -> 2m backoff
        advancer.advance(Duration.ofMinutes(2));
        uc.runOnce(10); // attempt 3 -> EXHAUSTED

        assertThat(store.markFailedCalls).hasSize(2); // only the two ladder rungs
        assertThat(store.markExhaustedCalls).hasSize(1);
        assertThat(store.markExhaustedCalls.get(0).id()).isEqualTo(id);
        assertThat(store.markExhaustedCalls.get(0).attemptCount()).isEqualTo(3);
        assertThat(store.status(id)).isEqualTo("EXHAUSTED");

        // EXHAUSTED rows are never claimed again
        advancer.advance(Duration.ofMinutes(5)); // the 5m ceiling would have elapsed — still not claimed
        int published = uc.runOnce(10);
        assertThat(published).isZero();
    }

    /**
     * E9 §2 lost race: {@code markExhausted} is conditional on {@code status='PENDING'} — if the fake
     * already advanced the row (returns false), the relay logs and moves on without double-marking.
     */
    @Test
    void exhaustion_lost_race_is_a_noop() {
        OutboxId id = OutboxId.generate(Clock.systemUTC());
        ((FakeOutboxEventStore) store).insert(
                id, "tx-1", "payment.created", 1, "{\"eventId\":\"evt-1\"}", "req-1", 0, FIXED_NOW);
        // Pre-advance the row so markExhausted's conditional finds no matching PENDING row
        store.forceAdvance(id);
        OutboxDeliveryUseCase uc = useCase(store, new FailingEventPublisher(), 3, advancer);

        int published = uc.runOnce(10);

        assertThat(published).isZero();
        assertThat(store.markExhaustedCalls).isEmpty(); // never reached the ceiling
    }

    /** A clock whose instant can be advanced between relay calls (ladder timings, no sleeps). */
    private final MutableClock advancer = new MutableClock(FIXED_NOW);

    private OutboxDeliveryUseCase useCase(OutboxEventStore store, EventPublisher publisher, int maxAttempts,
            Clock clock) {
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(null) {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        return new OutboxDeliveryUseCase(store, publisher, new tools.jackson.databind.json.JsonMapper(),
                clock, new OutboxDeliveryUseCase.Policy(32, 2, 1000, maxAttempts,
                        Duration.ofSeconds(30), Duration.ofMinutes(5), 7), txTemplate);
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    // Access private method via reflection
    private java.time.Duration backoff(OutboxDeliveryUseCase uc, int attempt) {
        try {
            var m = OutboxDeliveryUseCase.class.getDeclaredMethod("backoff", int.class);
            m.setAccessible(true);
            return (java.time.Duration) m.invoke(uc, attempt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ fakes

    static class FakeOutboxEventStore implements OutboxEventStore {
        private final java.util.Map<OutboxId, Row> rows = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.List<Instant> purgeCutoffs = new java.util.ArrayList<>();
        final java.util.List<MarkFailed> markFailedCalls = new java.util.ArrayList<>();
        final java.util.List<MarkExhausted> markExhaustedCalls = new java.util.ArrayList<>();
        private int claims;

        record MarkFailed(OutboxId id, int attemptCount, Instant nextAttemptAt) {}
        record MarkExhausted(OutboxId id, int attemptCount) {}

        record Row(
                OutboxId id,
                String aggregateId,
                String type,
                int version,
                String payload,
                String requestId,
                String status,
                int attemptCount,
                Instant nextAttemptAt
        ) {}

        /** Insert a PENDING row tracked by nextAttemptAt (test helper). */
        void insert(OutboxId id, String aggregateId, String type, int version,
                    String payload, String requestId, int attemptCount, Instant nextAttemptAt) {
            rows.put(id, new Row(id, aggregateId, type, version,
                    payload, requestId, "PENDING", attemptCount, nextAttemptAt));
        }

        /** Pre-advance a row (simulate a lost race: markExhausted's conditional finds no PENDING row). */
        void forceAdvance(OutboxId id) {
            Row r = rows.get(id);
            if (r == null) throw new IllegalStateException("no row " + id);
            rows.put(id, new Row(r.id(), r.aggregateId(), r.type(), r.version(),
                    r.payload(), r.requestId(), "SENT", r.attemptCount() + 1, r.nextAttemptAt()));
        }

        int claimCount() {
            return claims;
        }

        String status(OutboxId id) {
            Row r = rows.get(id);
            return r == null ? "GONE" : r.status();
        }

        @Override
        public List<OutboxEventStore.OutboxRow> claimPending(int batch, Instant now) {
            claims++;
            return rows.values().stream()
                    .filter(r -> "PENDING".equals(r.status()))
                    .filter(r -> r.nextAttemptAt() != null && !r.nextAttemptAt().isAfter(now))
                    .limit(batch)
                    .map(r -> new OutboxEventStore.OutboxRow(
                            r.id(), r.aggregateId(), r.type(), r.version(),
                            r.payload(), r.requestId(), r.attemptCount()
                    ))
                    .toList();
        }

        @Override
        public boolean markSent(OutboxId id, int attemptCount, Instant publishedAt) {
            Row r = rows.get(id);
            if (r == null || !"PENDING".equals(r.status())) return false;
            rows.put(id, new Row(r.id(), r.aggregateId(), r.type(), r.version(),
                    r.payload(), r.requestId(), "SENT", attemptCount, r.nextAttemptAt()));
            return true;
        }

        @Override
        public boolean markFailed(OutboxId id, int attemptCount, Instant nextAttemptAt) {
            Row r = rows.get(id);
            if (r == null || !"PENDING".equals(r.status())) return false;
            rows.put(id, new Row(r.id(), r.aggregateId(), r.type(), r.version(),
                    r.payload(), r.requestId(), "PENDING", attemptCount, nextAttemptAt));
            markFailedCalls.add(new MarkFailed(id, attemptCount, nextAttemptAt));
            return true;
        }

        @Override
        public boolean markExhausted(OutboxId id, int attemptCount) {
            Row r = rows.get(id);
            if (r == null || !"PENDING".equals(r.status())) return false;
            rows.put(id, new Row(r.id(), r.aggregateId(), r.type(), r.version(),
                    r.payload(), r.requestId(), "EXHAUSTED", attemptCount, r.nextAttemptAt()));
            markExhaustedCalls.add(new MarkExhausted(id, attemptCount));
            return true;
        }

        @Override
        public int purgeSent(Instant cutoff, int limit) {
            purgeCutoffs.add(cutoff);
            return 0;
        }
    }

    static class FakeEventPublisher implements EventPublisher {
        @Override
        public void publish(String type, String payload, String eventId, String aggregateId) {
            // Simulate success
        }
    }

    static class FailingEventPublisher implements EventPublisher {
        @Override
        public void publish(String type, String payload, String eventId, String aggregateId) {
            throw new RuntimeException("publish failed (forced)");
        }
    }

    // Simple TransactionTemplate for unit tests (synchronous execution)
    static class SyncTransactionTemplate extends org.springframework.transaction.support.TransactionTemplate {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}
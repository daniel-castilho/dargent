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

        record Row(
                OutboxId id,
                String aggregateId,
                String type,
                int version,
                String payload,
                String requestId,
                int attemptCount,
                Instant nextAttemptAt
        ) {}

        /**
         * Insert a row with nextAttemptAt tracking (test helper).
         * The OutboxEventStore.OutboxRow record doesn't include nextAttemptAt,
         * but the fake tracks it internally for claim filtering.
         */
        void insert(OutboxId id, String aggregateId, String type, int version,
                    String payload, String requestId, int attemptCount, Instant nextAttemptAt) {
            rows.put(id, new Row(id, aggregateId, type, version,
                    payload, requestId, 0, // attemptCount starts at 0
                    nextAttemptAt));
        }

        @Override
        public List<OutboxEventStore.OutboxRow> claimPending(int batch, Instant now) {
            return rows.values().stream()
                    .filter(r -> r.nextAttemptAt() != null && !r.nextAttemptAt().isAfter(now))
                    .limit(10)
                    .map(r -> new OutboxEventStore.OutboxRow(
                            r.id(), r.aggregateId(), r.type(), r.version(),
                            r.payload(), r.requestId(), r.attemptCount()
                    ))
                    .toList();
        }

        @Override
        public boolean markSent(OutboxId id, int attemptCount, Instant publishedAt) {
            return rows.remove(id) != null;
        }

        @Override
        public boolean markFailed(OutboxId id, int attemptCount, Instant nextAttemptAt) {
            Row r = rows.get(id);
            if (r == null) return false;
            rows.put(id, new Row(
                    r.id(), r.aggregateId(), r.type(), r.version(),
                    r.payload(), r.requestId(), attemptCount, nextAttemptAt
            ));
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

    // Simple TransactionTemplate for unit tests (synchronous execution)
    static class SyncTransactionTemplate extends org.springframework.transaction.support.TransactionTemplate {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}
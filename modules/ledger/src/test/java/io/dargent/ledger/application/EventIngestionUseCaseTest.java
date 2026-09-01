package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.EntryDirection;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.port.out.LedgerStore;
import io.dargent.shared.events.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EventIngestionUseCaseTest {

    private EventIngestionUseCase useCase;
    private FakeLedgerStore store;
    private Clock fixedClock;
    private TransactionTemplate txTemplate;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        store = new FakeLedgerStore();
        fixedClock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), java.time.ZoneOffset.UTC);
        var reader = new EventEnvelopeReader();
        jdbc = mock(JdbcClient.class);
        var spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        // Mock TransactionTemplate to execute callback directly
        txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        useCase = new EventIngestionUseCase(new EventEnvelopeReader(), store, jdbc, txTemplate, fixedClock);
    }

    @Test
    void processes_confirmed_payment_and_acks() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                java.util.UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}"
        );
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(envelope);

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
        // Verify JDBC was called to update status to POSTED
        verify(jdbc).sql(org.mockito.ArgumentMatchers.argThat(s -> s.toString().contains("UPDATE ledger.events") && s.toString().contains("status = 'POSTED'")));
    }

    @Test
    void duplicate_event_is_acked_and_skipped() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                java.util.UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}"
        );
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(envelope);

        boolean ack1 = useCase.processMessage(raw);
        boolean ack2 = useCase.processMessage(raw);

        assertThat(ack1).isTrue();
        assertThat(ack2).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
    }

    @Test
    void non_confirmed_event_is_ignored() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                java.util.UUID.randomUUID(),
                "payment.created",
                1,
                "txid-123",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{}"
        );
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(envelope);

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
        // Non-confirmed events are IGNORED, no JDBC UPDATE called
        var eventId = store.insertedEvents.keySet().iterator().next();
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("IGNORED");
    }

    @Test
    void malformed_payload_rejected() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                java.util.UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "not valid json"
        );
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(envelope);

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
        var eventId = store.insertedEvents.keySet().iterator().next();
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("REJECTED");
    }

    @Test
    void fee_plus_net_must_equal_amount() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                java.util.UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":200,\"net\":9900,\"late\":false}"
        );
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(envelope);

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        var eventId = store.insertedEvents.keySet().iterator().next();
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("REJECTED");
    }

    @Test
    void postings_balanced_debit_credit() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                java.util.UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}"
        );
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(envelope);

        useCase.processMessage(raw);

        var entry = store.postedEntries.get(0);
        long totalDebit = entry.postings().stream()
                .filter(p -> p.direction() == io.dargent.ledger.domain.model.EntryDirection.DEBIT)
                .mapToLong(io.dargent.ledger.domain.model.Posting::amountCents)
                .sum();
        long totalCredit = entry.postings().stream()
                .filter(p -> p.direction() == io.dargent.ledger.domain.model.EntryDirection.CREDIT)
                .mapToLong(io.dargent.ledger.domain.model.Posting::amountCents)
                .sum();

        assertThat(totalDebit).isEqualTo(totalCredit);
    }

    // Fake LedgerStore for testing
    static class FakeLedgerStore implements LedgerStore {
        final ConcurrentHashMap<UUID, EventRecord> insertedEvents = new ConcurrentHashMap<>();
        final java.util.List<JournalEntry> postedEntries = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ConcurrentHashMap<String, Account> balances = new ConcurrentHashMap<>();

        record EventRecord(UUID eventId, String type, String txid, UUID merchantId,
                           String payload, String status, String note) {}

        @Override
        public boolean insertEventIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
                                           String payload, String status, String note) {
            EventRecord record = new EventRecord(eventId, type, txid, merchantId, payload, status, note);
            insertedEvents.put(eventId, record); // Always update to capture status changes
            return true;
        }

        @Override
        public void postJournal(JournalEntry entry) {
            postedEntries.add(entry);
            for (var p : entry.postings()) {
                long delta = p.direction() == io.dargent.ledger.domain.model.EntryDirection.CREDIT ? p.amountCents() : -p.amountCents();
                balances.compute(p.account(), (k, v) -> v == null
                        ? new Account(p.account(), delta, java.time.Instant.now(), entry.eventId())
                        : v.credit(delta, java.time.Instant.now(), entry.eventId()));
            }
        }

        @Override
        public void upsertBalance(Account account) {
            balances.put(account.account(), account);
        }

        @Override
        public Optional<Account> findAccount(String account) {
            return Optional.ofNullable(balances.get(account));
        }

        @Override
        public long availableBalance(UUID merchantId) {
            return findAccount("merchant:" + merchantId + ":available").map(Account::balanceCents).orElse(0L);
        }

        @Override
        public Optional<io.dargent.ledger.domain.model.Settlement> insertSettlement(
                io.dargent.ledger.domain.model.Settlement settlement) {
            return Optional.empty();
        }

        @Override
        public Optional<io.dargent.ledger.domain.model.Account> lockAvailableBalance(UUID merchantId) {
            return Optional.empty();
        }

        @Override
        public Optional<io.dargent.ledger.domain.model.Settlement> findSettlementByKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public void rebuildBalances() {
        }

        @Override
        public void recordAudit(LedgerStore.AuditEntry audit) {
        }

        @Override
        public ProofResult verifyProof() {
            return new ProofResult(true, null, 0, 0, 0);
        }
    }
}
package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.EntryDirection;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.port.out.LedgerStore;
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
    void processes_confirmed_payment_and_acks() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
        // Verify JDBC was called to update status to POSTED
        verify(jdbc).sql(org.mockito.ArgumentMatchers.argThat(s -> s.toString().contains("UPDATE ledger.events") && s.toString().contains("status = 'POSTED'")));
    }

    @Test
    void duplicate_event_is_acked_and_skipped() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        boolean ack1 = useCase.processMessage(raw);
        boolean ack2 = useCase.processMessage(raw);

        assertThat(ack1).isTrue();
        assertThat(ack2).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
    }

    @Test
    void non_confirmed_event_is_ignored() {
        String raw = rawEnvelope("payment.created", "{}");

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
        // Non-confirmed events are IGNORED, no JDBC UPDATE called
        var eventId = store.insertedEvents.keySet().iterator().next();
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("IGNORED");
    }

    @Test
    void malformed_payload_rejected() {
        String raw = rawEnvelope("payment.confirmed", "\"not valid json\"");

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedEvents).hasSize(1);
        var eventId = store.insertedEvents.keySet().iterator().next();
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("REJECTED");
    }

    @Test
    void fee_plus_net_must_equal_amount() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":200,\"net\":9900,\"late\":false}");

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        var eventId = store.insertedEvents.keySet().iterator().next();
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("REJECTED");
    }

    // BD-15: duplicate-branch matrix by stored status

    @Test
    void duplicate_received_resumes_and_posts_once() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        // Pre-insert an event in RECEIVED state (simulating crash after insert, before journal)
        String eventIdStr = extractEventId(raw);
        UUID eventId = UUID.fromString(eventIdStr);
        store.insertedEvents.put(eventId, new FakeLedgerStore.EventRecord(
                eventId, "payment.confirmed", "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                raw, "RECEIVED", "Initial receipt"));

        // Call processMessage - should resume from RECEIVED
        boolean ack = useCase.processMessage(raw);
        assertThat(ack).isTrue();
        assertThat(store.postedEntries).hasSize(1);
        assertThat(store.insertedEvents.get(eventId).status()).isEqualTo("POSTED");
    }

    @Test
    void duplicate_posted_ack_skips() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        // Pre-insert an event in POSTED state
        String eventIdStr = extractEventId(raw);
        UUID eventId = UUID.fromString(eventIdStr);
        store.insertedEvents.put(eventId, new FakeLedgerStore.EventRecord(
                eventId, "payment.confirmed", "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                raw, "POSTED", "Posted successfully"));
        var clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), java.time.ZoneOffset.UTC);
        store.postedEntries.add(new JournalEntry(
                UUID.randomUUID(), eventId, "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Payment confirmed: txid-123", clock.instant(),
                List.of(
                        new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:debit", EntryDirection.DEBIT, 1000, clock.instant()),
                        new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:credit", EntryDirection.CREDIT, 1000, clock.instant())
                )));

        boolean ack = useCase.processMessage(raw);
        assertThat(ack).isTrue();
        // No new posting
        assertThat(store.postedEntries).hasSize(1);
    }

    @Test
    void duplicate_ignored_ack_skips() {
        String raw = rawEnvelope("payment.created", "{}");

        // Pre-insert an event in IGNORED state
        String eventIdStr = extractEventId(raw);
        UUID eventId = UUID.fromString(eventIdStr);
        store.insertedEvents.put(eventId, new FakeLedgerStore.EventRecord(
                eventId, "payment.created", "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                raw, "IGNORED", "Non-confirmed type: payment.created"));

        boolean ack = useCase.processMessage(raw);
        assertThat(ack).isTrue();
        assertThat(store.postedEntries).isEmpty();
    }

    @Test
    void duplicate_rejected_ack_skips() {
        String raw = rawEnvelope("payment.confirmed", "\"not valid json\"");

        // Pre-insert an event in REJECTED state
        String eventIdStr = extractEventId(raw);
        UUID eventId = UUID.fromString(eventIdStr);
        store.insertedEvents.put(eventId, new FakeLedgerStore.EventRecord(
                eventId, "payment.confirmed", "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                raw, "REJECTED", "Invalid envelope: ..."));

        boolean ack = useCase.processMessage(raw);
        assertThat(ack).isTrue();
        assertThat(store.postedEntries).isEmpty();
    }

    @Test
    void concurrent_resume_race_lost_ack_skips() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        // Pre-insert an event in RECEIVED state
        String eventIdStr = extractEventId(raw);
        UUID eventId = UUID.fromString(eventIdStr);
        store.insertedEvents.put(eventId, new FakeLedgerStore.EventRecord(
                eventId, "payment.confirmed", "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                raw, "RECEIVED", "Initial receipt"));

        // Simulate another consumer winning the claim by setting status to POSTED
        store.insertedEvents.put(eventId, new FakeLedgerStore.EventRecord(
                eventId, "payment.confirmed", "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                raw, "POSTED", "Posted successfully"));
        var clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), java.time.ZoneOffset.UTC);
        store.postedEntries.add(new JournalEntry(
                UUID.randomUUID(), eventId, "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Payment confirmed: txid-123", clock.instant(),
                List.of(
                        new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:debit", EntryDirection.DEBIT, 1000, clock.instant()),
                        new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:credit", EntryDirection.CREDIT, 1000, clock.instant())
                )));

        boolean ack = useCase.processMessage(raw);
        assertThat(ack).isTrue();
        // No new posting — the other consumer already did it
        assertThat(store.postedEntries).hasSize(1);
    }

    private String extractEventId(String raw) {
        // Parse eventId from raw JSON
        int start = raw.indexOf("\"eventId\":\"") + 11;
        int end = raw.indexOf("\"", start);
        return raw.substring(start, end);
    }

    @Test
    void postings_balanced_debit_credit() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

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

    private static String rawEnvelope(String type, String payloadJson) {
        return "{\"eventId\":\"" + java.util.UUID.randomUUID()
                + "\",\"type\":\"" + type
                + "\",\"version\":1,\"aggregateId\":\"txid-123\""
                + ",\"merchantId\":\"11111111-1111-1111-1111-111111111111\""
                + ",\"requestId\":\"req-123\",\"occurredAt\":\"2026-08-30T12:00:00Z\""
                + ",\"payload\":" + payloadJson + "}";
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
            if (insertedEvents.containsKey(eventId)) {
                return false; // Duplicate
            }
            EventRecord record = new EventRecord(eventId, type, txid, merchantId, payload, status, note);
            insertedEvents.put(eventId, record);
            return true;
        }

        @Override
        public Optional<String> findEventStatus(UUID eventId) {
            return Optional.ofNullable(insertedEvents.get(eventId))
                    .map(EventRecord::status);
        }

        @Override
        public int claimEventForResume(UUID eventId) {
            EventRecord record = insertedEvents.get(eventId);
            if (record != null && "RECEIVED".equals(record.status())) {
                // Update to POSTED (mutable copy for test)
                insertedEvents.put(eventId, new EventRecord(
                        record.eventId(), record.type(), record.txid(), record.merchantId(),
                        record.payload(), "POSTED", "Posted successfully"));
                return 1;
            }
            return 0;
        }

        @Override
        public void postJournal(JournalEntry entry) {
            postedEntries.add(entry);
            // Update event status to POSTED (simulating the real implementation's UPDATE)
            insertedEvents.computeIfPresent(entry.eventId(), (id, record) ->
                    new EventRecord(record.eventId(), record.type(), record.txid(), record.merchantId(),
                            record.payload(), "POSTED", "Posted successfully"));
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

        @Override
        public boolean postRefund(UUID eventId, String txid, UUID merchantId, long amountCents,
                long feeReversalCents, String description, Instant createdAt, Clock clock) {
            return false; // No-op for test
        }
    }
}
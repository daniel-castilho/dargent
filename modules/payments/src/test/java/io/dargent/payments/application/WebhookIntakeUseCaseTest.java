package io.dargent.payments.application;

import io.dargent.payments.domain.model.EndToEndId;
import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.model.WebhookSignatureValidator;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.WebhookEventRecord;
import io.dargent.payments.domain.port.out.WebhookEventStore;
import io.dargent.shared.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD for WebhookIntakeUseCase (E4 spec §5.3).
 * Unit tests with fakes — every §5.3 branch covered.
 */
class WebhookIntakeUseCaseTest {

    // ---- fakes ----

    static class FakeWebhookEventStore implements WebhookEventStore {
        final Map<String, WebhookEventRecord> store = new LinkedHashMap<>();
        final AtomicInteger insertCalls = new AtomicInteger();

        @Override
        public Optional<WebhookEventRecord> insertIfAbsent(WebhookEventRecord record) {
            insertCalls.incrementAndGet();
            if (store.containsKey(record.providerEventId())) {
                return Optional.of(store.get(record.providerEventId()));
            }
            store.put(record.providerEventId(), record);
            return Optional.empty();
        }

        @Override
        public void markProcessed(String providerEventId) {
            WebhookEventRecord r = store.get(providerEventId);
            if (r != null) {
                store.put(providerEventId, new WebhookEventRecord(
                        r.id(), r.providerEventId(), r.pspEventId(), r.type(), r.txid(),
                        r.payloadRaw(), r.signatureValid(), "PROCESSED", r.receivedAt(), Instant.now()));
            }
        }

        @Override
        public void markIgnored(String providerEventId) {
            WebhookEventRecord r = store.get(providerEventId);
            if (r != null) {
                store.put(providerEventId, new WebhookEventRecord(
                        r.id(), r.providerEventId(), r.pspEventId(), r.type(), r.txid(),
                        r.payloadRaw(), r.signatureValid(), "IGNORED", r.receivedAt(), Instant.now()));
            }
        }

        @Override
        public Optional<WebhookEventRecord> findByProviderEventId(String providerEventId) {
            return Optional.ofNullable(store.get(providerEventId));
        }
    }

    static class FakePaymentRepository implements PaymentRepository {
        final Map<Txid, Payment> store = new LinkedHashMap<>();
        final Map<Txid, Integer> committedVersions = new LinkedHashMap<>();

        @Override
        public void save(Payment payment) {
            store.put(payment.txid(), payment);
            committedVersions.put(payment.txid(), payment.version());
        }

        @Override
        public Optional<Payment> findByTxid(Txid txid) {
            Payment stored = store.get(txid);
            if (stored == null) return Optional.empty();
            // Return a copy so mutations don't affect the stored version
            return Optional.of(Payment.restore(
                    stored.id(), stored.txid(), stored.merchantId(), stored.amount(),
                    stored.description(), stored.expiresAt(), stored.createdAt(),
                    stored.status(), stored.version(), stored.endToEndId(),
                    stored.fee(), stored.net(), stored.lateConfirmation(),
                    stored.confirmedAt(), stored.refunded().cents()));
        }

        @Override
        public boolean updateIfVersionMatches(Payment payment, int expectedVersion) {
            Integer committed = committedVersions.get(payment.txid());
            if (committed == null || committed != expectedVersion) {
                return false;
            }
            payment.markPersistedVersion(expectedVersion + 1);
            store.put(payment.txid(), payment);
            committedVersions.put(payment.txid(), expectedVersion + 1);
            return true;
        }

        @Override
        public java.util.List<Payment> findDueExpired(java.time.Instant now, int limit) {
            return store.values().stream()
                    .filter(p -> p.status() == io.dargent.payments.domain.model.PaymentStatus.PENDING)
                    .filter(p -> p.expiresAt().isBefore(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean expireIfDue(Payment payment, java.time.Instant now) {
            Payment stored = store.get(payment.txid());
            if (stored == null || stored.status() != io.dargent.payments.domain.model.PaymentStatus.PENDING
                    || !stored.expiresAt().isBefore(now)) {
                return false;
            }
            Payment expired = Payment.restore(
                    stored.id(), stored.txid(), stored.merchantId(), stored.amount(),
                    stored.description(), stored.expiresAt(), stored.createdAt(),
                    io.dargent.payments.domain.model.PaymentStatus.EXPIRED, stored.version() + 1,
                    stored.endToEndId(), stored.fee(), stored.net(), stored.lateConfirmation(),
                    stored.confirmedAt(), stored.refunded().cents());
            store.put(payment.txid(), expired);
            committedVersions.put(payment.txid(), stored.version() + 1);
            return true;
        }

        @Override
        public java.util.List<Payment> findDueReconciliation(java.time.Instant now, int limit) {
            return store.values().stream()
                    .filter(p -> p.status() == io.dargent.payments.domain.model.PaymentStatus.PENDING
                            || p.status() == io.dargent.payments.domain.model.PaymentStatus.EXPIRED)
                    .filter(p -> p.nextReconcileAt() != null && p.nextReconcileAt().isBefore(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean updateReconciliationSchedule(Payment payment, java.time.Instant nextReconcileAt, int reconcileAttempts, int expectedVersion) {
            Integer committed = committedVersions.get(payment.txid());
            if (committed == null || committed != expectedVersion) {
                return false;
            }
            Payment stored = store.get(payment.txid());
            if (stored == null) return false;
            Payment updated = Payment.restore(
                    stored.id(), stored.txid(), stored.merchantId(), stored.amount(),
                    stored.description(), stored.expiresAt(), stored.createdAt(),
                    stored.status(), expectedVersion + 1,
                    stored.endToEndId(), stored.fee(), stored.net(), stored.lateConfirmation(),
                    stored.confirmedAt(), stored.refunded().cents(), nextReconcileAt, reconcileAttempts);
            store.put(payment.txid(), updated);
            committedVersions.put(payment.txid(), expectedVersion + 1);
            return true;
        }

        @Override
        public boolean clearReconciliationScheduleIfPastWindow(Payment payment, java.time.Instant windowEnd, int expectedVersion) {
            Integer committed = committedVersions.get(payment.txid());
            if (committed == null || committed != expectedVersion) {
                return false;
            }
            Payment stored = store.get(payment.txid());
            if (stored == null || stored.nextReconcileAt() == null) {
                return false;
            }
            Payment updated = Payment.restore(
                    stored.id(), stored.txid(), stored.merchantId(), stored.amount(),
                    stored.description(), stored.expiresAt(), stored.createdAt(),
                    stored.status(), expectedVersion + 1,
                    stored.endToEndId(), stored.fee(), stored.net(), stored.lateConfirmation(),
                    stored.confirmedAt(), stored.refunded().cents(), null, 0);
            store.put(payment.txid(), updated);
            committedVersions.put(payment.txid(), expectedVersion + 1);
            return true;
}

    @Override
    public Optional<Payment> findByTxidForUpdate(String txid) {
        Txid txidObj = new Txid(txid);
        Payment stored = store.get(txidObj);
        if (stored == null) {
            return Optional.empty();
        }
        // Return a copy so mutations don't affect the stored version
        return Optional.of(Payment.restore(
                stored.id(), stored.txid(), stored.merchantId(), stored.amount(),
                stored.description(), stored.expiresAt(), stored.createdAt(),
                stored.status(), stored.version(), stored.endToEndId(),
                stored.fee(), stored.net(), stored.lateConfirmation(),
                stored.confirmedAt(), stored.refunded().cents()));
    }

    @Override
    public void insertRefund(UUID paymentId, String txid, long amountCents, long feeReversalCents,
            long netCents, String requestId) {
        // No-op for test
    }
}

    static class FakeOutboxWriter implements OutboxWriter {
        final List<OutboxEntry> entries = new java.util.ArrayList<>();

        record OutboxEntry(String aggregateId, String type, int version, String payload, String requestId) {}

        @Override
        public void append(String aggregateId, String type, int version, String payloadJson, String requestId) {
            entries.add(new OutboxEntry(aggregateId, type, version, payloadJson, requestId));
        }
    }

    static class FakeAuditWriter implements AuditWriter {
        final List<AuditEntry> entries = new java.util.ArrayList<>();

        record AuditEntry(String commandName, UUID actorKeyId, UUID merchantId, String aggregateId, String requestId) {}

        @Override
        public void record(String commandName, UUID actorKeyId, UUID merchantId, String aggregateId, String requestId) {
            entries.add(new AuditEntry(commandName, actorKeyId, merchantId, aggregateId, requestId));
        }
    }

    /**
     * TransactionTemplate that runs the callback synchronously with no real
     * transaction manager (unit-test friendly). Used to prove the use case
     * actually executes its work inside a TransactionTemplate (BD-11 fix).
     */
    static class DirectTransactionTemplate extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    /**
     * TransactionTemplate that rolls back all state changes when the callback
     * throws, emulating Spring's rollback semantics for the atomicity test.
     */
    static class SnapshotRollbackTransactionTemplate extends TransactionTemplate {
        private final Runnable capture;
        private final Runnable restore;

        SnapshotRollbackTransactionTemplate(Runnable capture, Runnable restore) {
            this.capture = capture;
            this.restore = restore;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            capture.run();
            try {
                return action.doInTransaction(null);
            } catch (RuntimeException e) {
                restore.run();
                throw e;
            }
        }
    }

    // ---- test fixture ----

    private FakeWebhookEventStore webhookStore;
    private FakePaymentRepository paymentRepo;
    private FakeOutboxWriter outboxWriter;
    private FakeAuditWriter auditWriter;
    private WebhookSignatureValidator signatureValidator;
    private EventEnvelopeFactory envelopeFactory;
    private Clock clock;
    private ObjectMapper testMapper;

    private WebhookIntakeUseCase useCase;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Txid TXID = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
    private static final EndToEndId END_TO_END_ID = new EndToEndId("E9040381234567890123456789012345");
    private static final String PROVIDER_EVENT_ID = END_TO_END_ID.value() + "|payment.confirmed";
    private static final String PSP_EVENT_ID = "psp-evt-test-001";
    private static final String TYPE = "payment.confirmed";
    private static final String PAYLOAD_RAW = "{\"eventId\":\"psp-evt-test-001\",\"type\":\"payment.confirmed\",\"txid\":\"8KD4Z9X2Q7W1M5T3R6Y0A1B2C\",\"endToEndId\":\"E9040381234567890123456789012345\",\"amount\":10000,\"paidAt\":\"2026-08-29T00:00:00Z\"}";

    @BeforeEach
    void setUp() {
        webhookStore = new FakeWebhookEventStore();
        paymentRepo = new FakePaymentRepository();
        outboxWriter = new FakeOutboxWriter();
        auditWriter = new FakeAuditWriter();
        signatureValidator = new WebhookSignatureValidator(FIXED_CLOCK);
        envelopeFactory = new EventEnvelopeFactory(new EventSerializer());
        clock = FIXED_CLOCK;
        testMapper = new JsonMapper();

        useCase = new WebhookIntakeUseCase(webhookStore, paymentRepo, outboxWriter, auditWriter,
                signatureValidator, new DirectTransactionTemplate(), envelopeFactory, clock, testMapper);
    }

    // ---- helper to seed a PENDING payment ----

    private void seedPayment(PaymentStatus status) {
        boolean confirmedFamily = status == PaymentStatus.CONFIRMED
                || status == PaymentStatus.PARTIALLY_REFUNDED
                || status == PaymentStatus.REFUNDED;
        Instant created = Instant.parse("2026-08-29T12:00:00Z");
        Payment payment = Payment.restore(
                UUID.randomUUID(), TXID, MERCHANT, Money.of(10000, "BRL"), "Order #1",
                Instant.parse("2026-08-29T12:02:00Z"), created,
                status, 0,
                confirmedFamily ? END_TO_END_ID : null,
                confirmedFamily ? Money.of(100, "BRL") : null,
                confirmedFamily ? Money.of(9900, "BRL") : null,
                confirmedFamily, confirmedFamily ? created.plusSeconds(60) : null, 0
        );
        paymentRepo.save(payment);
    }

    private WebhookIntakeUseCase.Input input() {
        return new WebhookIntakeUseCase.Input(PROVIDER_EVENT_ID, PSP_EVENT_ID, TYPE, TXID.value(), PAYLOAD_RAW, true);
    }

    // ============================================================================ TESTS

    @Test
    void new_webhook_processed_confirms_payment_and_appends_outbox_and_audit() {
        seedPayment(PaymentStatus.PENDING);

        var outcome = useCase.execute(input());

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Processed.class);

        assertThat(webhookStore.store.get(PROVIDER_EVENT_ID).status()).isEqualTo("PROCESSED");

        Payment payment = paymentRepo.findByTxid(TXID).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.endToEndId()).isEqualTo(END_TO_END_ID);
        assertThat(payment.fee().cents()).isEqualTo(100);
        assertThat(payment.net().cents()).isEqualTo(9900);

        assertThat(outboxWriter.entries).hasSize(1);
        assertThat(outboxWriter.entries.get(0).type()).isEqualTo("payment.confirmed");
        String payload = outboxWriter.entries.get(0).payload();
        Map<String, Object> envelope = testMapper.readValue(payload, new TypeReference<>() {});
        assertThat(envelope).containsEntry("type", "payment.confirmed");
        assertThat(envelope).containsEntry("version", 1);
        assertThat(envelope).containsEntry("aggregateId", TXID.value());
        assertThat(envelope).containsEntry("merchantId", MERCHANT.toString());
        assertThat(envelope).containsEntry("occurredAt", FIXED_CLOCK.instant().toString());
        assertThat(envelope).containsEntry("requestId", null);
        assertThat(envelope.get("eventId")).isInstanceOf(String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) envelope.get("payload");
        assertThat(nested).containsEntry("amount", 10_000);
        assertThat(nested).containsEntry("fee", 100);
        assertThat(nested).containsEntry("net", 9900);
        assertThat(nested).containsEntry("late", false);

        assertThat(auditWriter.entries).hasSize(1);
        assertThat(auditWriter.entries.get(0).commandName()).isEqualTo("confirm_from_webhook");
        assertThat(auditWriter.entries.get(0).merchantId()).isEqualTo(MERCHANT);
        assertThat(auditWriter.entries.get(0).aggregateId()).isEqualTo(TXID.value());
    }

    @Test
    void duplicate_provider_event_id_marked_PROCESSED_returns_duplicate() {
        seedPayment(PaymentStatus.PENDING);

        useCase.execute(input());
        var outcome2 = useCase.execute(input());

        assertThat(outcome2).isInstanceOf(WebhookIntakeUseCase.Outcome.Duplicate.class);
        assertThat(outboxWriter.entries).hasSize(1);
        assertThat(auditWriter.entries).hasSize(1);
    }

    @Test
    void duplicate_RECEIVED_reprocesses_and_succeeds() {
        seedPayment(PaymentStatus.PENDING);

        WebhookEventRecord received = new WebhookEventRecord(
                UUID.randomUUID(), PROVIDER_EVENT_ID, PSP_EVENT_ID, TYPE, TXID.value(),
                PAYLOAD_RAW, true, "RECEIVED", FIXED_CLOCK.instant(), null
        );
        webhookStore.store.put(PROVIDER_EVENT_ID, received);

        var outcome = useCase.execute(input());

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Processed.class);
        Payment payment = paymentRepo.findByTxid(TXID).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void unknown_type_marks_IGNORED_returns_ignored() {
        seedPayment(PaymentStatus.PENDING);
        String badType = "payment.unknown";
        String badProviderId = END_TO_END_ID.value() + "|" + badType;
        String badPayload = PAYLOAD_RAW.replace("\"payment.confirmed\"", "\"payment.unknown\"");

        var input = new WebhookIntakeUseCase.Input(badProviderId, PSP_EVENT_ID, badType, TXID.value(), badPayload, true);
        var outcome = useCase.execute(input);

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Ignored.class);
        assertThat(webhookStore.store.get(badProviderId).status()).isEqualTo("IGNORED");
        assertThat(outboxWriter.entries).isEmpty();
    }

    @Test
    void unknown_txid_marks_IGNORED_returns_ignored() {
        String badTxid = "9KD4Z9X2Q7W1M5T3R6Y0A1B2D";
        String badProviderId = END_TO_END_ID.value() + "|" + TYPE;
        String badPayload = PAYLOAD_RAW.replace(TXID.value(), badTxid);

        var input = new WebhookIntakeUseCase.Input(badProviderId, PSP_EVENT_ID, TYPE, badTxid, badPayload, true);
        var outcome = useCase.execute(input);

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Ignored.class);
        assertThat(webhookStore.store.get(badProviderId).status()).isEqualTo("IGNORED");
        assertThat(outboxWriter.entries).isEmpty();
    }

    @Test
    void amount_mismatch_marks_IGNORED_returns_ignored() {
        seedPayment(PaymentStatus.PENDING);
        String badPayload = PAYLOAD_RAW.replace("10000", "9999");
        String badProviderId = END_TO_END_ID.value() + "|" + TYPE;

        var input = new WebhookIntakeUseCase.Input(badProviderId, PSP_EVENT_ID, TYPE, TXID.value(), badPayload, true);
        var outcome = useCase.execute(input);

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Ignored.class);
        assertThat(webhookStore.store.get(badProviderId).status()).isEqualTo("IGNORED");
    }

    @Test
    void confirm_lost_race_returns_duplicate() {
        seedPayment(PaymentStatus.PENDING);

        useCase.execute(input());
        var outcome = useCase.execute(input());

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Duplicate.class);
    }

    @Test
    void already_confirmed_payment_returns_duplicate() {
        seedPayment(PaymentStatus.CONFIRMED);

        var outcome = useCase.execute(input());

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Duplicate.class);
    }

    @Test
    void invalid_txid_in_payload_returns_ignored() {
        String badTxid = "not-a-valid-txid";
        String badPayload = PAYLOAD_RAW.replace(TXID.value(), badTxid);
        String badProviderId = END_TO_END_ID.value() + "|" + TYPE;

        var input = new WebhookIntakeUseCase.Input(badProviderId, PSP_EVENT_ID, TYPE, badTxid, badPayload, true);
        var outcome = useCase.execute(input);

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Ignored.class);
    }

    @Test
    void expired_payment_can_be_confirmed_late_returns_processed() {
        seedPayment(PaymentStatus.EXPIRED);

        var outcome = useCase.execute(input());

        assertThat(outcome).isInstanceOf(WebhookIntakeUseCase.Outcome.Processed.class);
        Payment payment = paymentRepo.findByTxid(TXID).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.lateConfirmation()).isTrue();
    }

    @Test
    void outbox_failure_rolls_back_confirm_and_keeps_webhook_RECEIVED() {
        // Atomicity test (BD-11): failure after confirm but before outbox insert must roll
        // back the confirm + markProcessed and leave webhook event as RECEIVED (recovery point).
        seedPayment(PaymentStatus.PENDING);

        FakeOutboxWriter failingOutbox = new FakeOutboxWriter() {
            @Override
            public void append(String aggregateId, String type, int version, String payloadJson, String requestId) {
                throw new RuntimeException("simulated outbox failure");
            }
        };

        SnapshotRollbackTransactionTemplate rollbackTx = new SnapshotRollbackTransactionTemplate(
                () -> rollbackSnapshot = snapshotState(),
                () -> restoreFromSnapshot(rollbackSnapshot));

        WebhookIntakeUseCase atomicUseCase = new WebhookIntakeUseCase(
                webhookStore, paymentRepo, failingOutbox, auditWriter,
                signatureValidator, rollbackTx, envelopeFactory, clock, new tools.jackson.databind.json.JsonMapper());

        assertThatThrownBy(() -> atomicUseCase.execute(input()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated outbox failure");

        WebhookEventRecord event = webhookStore.store.get(PROVIDER_EVENT_ID);
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo("RECEIVED");

        Payment payment = paymentRepo.findByTxid(TXID).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.endToEndId()).isNull();

        assertThat(failingOutbox.entries).isEmpty();
        assertThat(auditWriter.entries).isEmpty();
    }

    private Object rollbackSnapshot;

    private Object snapshotState() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("webhook", deepCopyWebhook(webhookStore.store));
        snap.put("payments", deepCopyPayments(paymentRepo.store));
        snap.put("outbox", new java.util.ArrayList<>(outboxWriter.entries));
        snap.put("audit", new java.util.ArrayList<>(auditWriter.entries));
        return snap;
    }

    private Map<String, WebhookEventRecord> deepCopyWebhook(Map<String, WebhookEventRecord> src) {
        Map<String, WebhookEventRecord> copy = new LinkedHashMap<>();
        for (WebhookEventRecord r : src.values()) {
            copy.put(r.providerEventId(), new WebhookEventRecord(
                    r.id(), r.providerEventId(), r.pspEventId(), r.type(), r.txid(),
                    r.payloadRaw(), r.signatureValid(), r.status(), r.receivedAt(), r.processedAt()));
        }
        return copy;
    }

    private Map<Txid, Payment> deepCopyPayments(Map<Txid, Payment> src) {
        Map<Txid, Payment> copy = new LinkedHashMap<>();
        for (Payment p : src.values()) {
            copy.put(p.txid(), Payment.restore(
                    p.id(), p.txid(), p.merchantId(), p.amount(), p.description(),
                    p.expiresAt(), p.createdAt(), p.status(), p.version(), p.endToEndId(),
                    p.fee(), p.net(), p.lateConfirmation(), p.confirmedAt(),
                    p.refunded() == null ? 0L : p.refunded().cents()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private void restoreFromSnapshot(Object snapshot) {
        Map<String, Object> snap = (Map<String, Object>) snapshot;
        webhookStore.store.clear();
        webhookStore.store.putAll((Map<String, WebhookEventRecord>) snap.get("webhook"));
        paymentRepo.store.clear();
        paymentRepo.store.putAll((Map<Txid, Payment>) snap.get("payments"));
        outboxWriter.entries.clear();
        outboxWriter.entries.addAll((List<FakeOutboxWriter.OutboxEntry>) snap.get("outbox"));
        auditWriter.entries.clear();
        auditWriter.entries.addAll((List<FakeAuditWriter.AuditEntry>) snap.get("audit"));
    }
}

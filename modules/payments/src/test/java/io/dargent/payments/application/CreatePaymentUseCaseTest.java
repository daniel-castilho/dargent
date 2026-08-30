package io.dargent.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dargent.payments.application.CreatePaymentUseCase.Input;
import io.dargent.payments.application.CreatePaymentUseCase.Output;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.PspPort.ChargeResult;
import io.dargent.payments.domain.port.out.PspPort.CreateChargeInput;
import io.dargent.payments.domain.port.out.TxidGenerator;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CreatePaymentUseCase contract (E3 spec §5.8) — R2, one named test per register branch.
 * Mocks for every port; a no-op TransactionTemplate runs the core lambda without a database.
 */
class CreatePaymentUseCaseTest {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ENDPOINT = "POST /v1/payments";
    private static final String REQUEST_ID = "req-12345";
    private static final String FINGERPRINT_SAME = "sha256-same";
    private static final String PIX_KEY = "dargent-dev-receber@example.com";
    private static final String RECEIVER_NAME = "Dargent Dev LTDA";
    private static final String RECEIVER_CITY = "SAO PAULO";
    private static final String CALLBACK_URL = "http://api-blue:8080/webhooks/psp";
    private static final Txid TXID = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
    private static final Txid TXID2 = new Txid("9KD4Z9X2Q7W1M5T3R6Y0A1B2C");
    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final Instant PSP_EXPIRES = Instant.parse("2026-08-30T10:30:00Z");
    private static final Money AMOUNT = Money.of(10_000, "BRL");
    private static final Duration EXPIRES_IN = Duration.ofMinutes(30);

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private PaymentRepository paymentRepo;
    private IdempotencyStore idempotencyStore;
    private OutboxWriter outboxWriter;
    private AuditWriter auditWriter;
    private PspPort pspPort;
    private TxidGenerator txidGenerator;
    private CreatePaymentUseCase useCase;

    @BeforeEach
    void setUp() {
        paymentRepo = mock(PaymentRepository.class);
        idempotencyStore = mock(IdempotencyStore.class);
        outboxWriter = mock(OutboxWriter.class);
        auditWriter = mock(AuditWriter.class);
        pspPort = mock(PspPort.class);
        txidGenerator = mock(TxidGenerator.class);
        TransactionTemplate txTemplate = new TransactionTemplate(new NoopTransactionManager());
        when(txidGenerator.generate()).thenReturn(TXID);
        when(paymentRepo.findByTxid(any())).thenReturn(Optional.of(savedPayment(NOW.plus(EXPIRES_IN))));
        when(paymentRepo.updateIfVersionMatches(any(), anyInt())).thenReturn(true);
        useCase = new CreatePaymentUseCase(paymentRepo, idempotencyStore, outboxWriter, auditWriter,
                pspPort, txidGenerator, txTemplate, new EventSerializer(mapper),
                PIX_KEY, RECEIVER_NAME, RECEIVER_CITY, CALLBACK_URL,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Input input() {
        return new Input(MERCHANT, KEY_ID, "idem-key", ENDPOINT, FINGERPRINT_SAME, REQUEST_ID,
                AMOUNT, "Order #1", EXPIRES_IN);
    }

    private Payment savedPayment(Instant expiresAt) {
        return Payment.create(TXID, MERCHANT, AMOUNT, "Order #1", expiresAt, NOW);
    }

    // --- row 1: first call ------------------------------------------------

    @Test
    void first_call_inserts_in_flight_runs_core_then_psp_returns_pending() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID, PSP_EXPIRES, "E2E-1", "br"));

        Output out = useCase.execute(input());

        assertThat(out.txid()).isEqualTo(TXID);
        assertThat(out.status()).isEqualTo(PaymentStatus.PENDING); // BD-2: never CONFIRMED on create
        assertThat(out.expiresAt()).isEqualTo(PSP_EXPIRES);        // PSP truth
        assertThat(out.brcode()).contains(PIX_KEY);
        assertThat(out.replay()).isFalse();

        verify(idempotencyStore).markCompleted(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void outbox_payload_is_serialized_json_and_carries_request_id() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID, PSP_EXPIRES, "E2E-1", "br"));

        useCase.execute(input());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).append(eq(TXID.value()), eq("payment.created"), eq(1),
                payload.capture(), eq(REQUEST_ID));
        Map<String, Object> parsed = mapper.readValue(payload.getValue(), new TypeReference<>() {});
        assertThat(parsed).containsEntry("txid", TXID.value());
        assertThat(parsed).containsEntry("merchantId", MERCHANT.toString());
        assertThat(parsed).containsEntry("amount", 10_000);
        assertThat(parsed).containsEntry("description", "Order #1");
        assertThat(parsed).containsEntry("expiresAt", NOW.plus(EXPIRES_IN).toString());
    }

    @Test
    void psp_create_charge_uses_configured_callback_url() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID, PSP_EXPIRES, "E2E-1", "br"));

        useCase.execute(input());

        ArgumentCaptor<CreateChargeInput> captor = ArgumentCaptor.forClass(CreateChargeInput.class);
        verify(pspPort).createCharge(captor.capture());
        assertThat(captor.getValue().callbackUrl()).isEqualTo(CALLBACK_URL); // BD-9: config, not a literal
        assertThat(captor.getValue().txid()).isEqualTo(TXID);
        assertThat(captor.getValue().amountCents()).isEqualTo(10_000);
        assertThat(captor.getValue().description()).isEqualTo("Order #1");
    }

    @Test
    void audit_records_authenticated_key_id_and_request_id() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID, PSP_EXPIRES, "E2E-1", "br"));

        useCase.execute(input());

        verify(auditWriter).record(eq("create_payment"), eq(KEY_ID), eq(MERCHANT),
                eq(TXID.value()), eq(REQUEST_ID)); // BD-7: real key id, never generated; BD-5: requestId
    }

    @Test
    void core_runs_payment_then_outbox_then_audit_before_psp() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID, PSP_EXPIRES, "E2E-1", "br"));

        useCase.execute(input());

        InOrder order = inOrder(paymentRepo, outboxWriter, auditWriter, pspPort);
        order.verify(paymentRepo).save(any());
        order.verify(outboxWriter).append(any(), any(), anyInt(), any(), any()); // core before PSP (BD-1)
        order.verify(auditWriter).record(any(), any(), any(), any(), any());
        order.verify(pspPort).createCharge(any());
    }

    // --- row 2: replay ----------------------------------------------------

    @Test
    void same_key_same_fingerprint_completed_replays_snapshot_with_zero_side_effects() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any()))
                .thenReturn(Optional.of(completedRecord()));

        Output out = useCase.execute(input());

        assertThat(out.replay()).isTrue();
        assertThat(out.txid()).isEqualTo(TXID);
        assertThat(out.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(out.expiresAt()).isEqualTo(PSP_EXPIRES);
        assertThat(out.brcode()).isEqualTo("brcode-for-replay");

        verify(paymentRepo, never()).save(any());
        verify(outboxWriter, never()).append(any(), any(), anyInt(), any(), any());
        verify(pspPort, never()).createCharge(any());
    }

    // --- row 3: fingerprint conflict --------------------------------------

    @Test
    void same_key_different_fingerprint_conflicts() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any()))
                .thenReturn(Optional.of(completedRecord()));
        Input conflicting = new Input(MERCHANT, KEY_ID, "idem-key", ENDPOINT, "sha256-other",
                REQUEST_ID, AMOUNT, "Order #1", EXPIRES_IN);

        assertThatThrownBy(() -> useCase.execute(conflicting))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        verify(paymentRepo, never()).save(any());
        verify(pspPort, never()).createCharge(any());
    }

    // --- row 4: in flight --------------------------------------------------

    @Test
    void key_in_flight_same_fingerprint_returns_in_flight_exception() {
        IdempotencyRecord inFlight = new IdempotencyRecord(MERCHANT, "idem-key", ENDPOINT,
                FINGERPRINT_SAME, "IN_FLIGHT", null, null, null);
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> useCase.execute(input()))
                .isInstanceOf(IdempotencyKeyInFlightException.class);

        verify(paymentRepo, never()).save(any());
        verify(pspPort, never()).createCharge(any());
    }

    // --- row 5: exhaustion -------------------------------------------------

    @Test
    void core_succeeded_psp_exhausted_marks_failed_deletes_key_and_throws_psp_unavailable() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenThrow(new RuntimeException("connect timeout"));

        assertThatThrownBy(() -> useCase.execute(input()))
                .isInstanceOf(PspUnavailableException.class)
                .hasMessage("psp_create_exhausted");

        ArgumentCaptor<Payment> failedCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepo).updateIfVersionMatches(failedCaptor.capture(), eq(0));
        assertThat(failedCaptor.getValue().status()).isEqualTo(PaymentStatus.FAILED); // BD-3/BD-4
        verify(outboxWriter).append(eq(TXID.value()), eq("payment.failed"), eq(1), any(), eq(REQUEST_ID));
        verify(idempotencyStore).delete(eq(MERCHANT), eq("idem-key"), eq(ENDPOINT)); // delete, not COMPLETED
    }

    @Test
    void exhaustion_lost_race_rereads_and_decides() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenThrow(new RuntimeException("gone"));
        when(paymentRepo.findByTxid(any()))
                .thenReturn(Optional.of(savedPayment(NOW.plus(EXPIRES_IN))))
                .thenReturn(Optional.of(savedPayment(NOW.plus(EXPIRES_IN)))); // fresh PENDING per re-read
        when(paymentRepo.updateIfVersionMatches(any(), anyInt())).thenReturn(false, true); // first loses, second wins

        assertThatThrownBy(() -> useCase.execute(input()))
                .isInstanceOf(PspUnavailableException.class);

        verify(paymentRepo, times(2)).updateIfVersionMatches(any(), anyInt()); // re-read + decide (BD-3)
    }

    // --- BD-3: PSP truth conditional update ---------------------------------

    @Test
    void psp_success_updates_expires_at_via_reread_aggregate_with_current_version() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID, PSP_EXPIRES, "E2E-1", "br"));

        useCase.execute(input());

        ArgumentCaptor<Payment> updatedCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepo).updateIfVersionMatches(updatedCaptor.capture(), eq(0));
        assertThat(updatedCaptor.getValue().expiresAt()).isEqualTo(PSP_EXPIRES); // PSP truth wins
        assertThat(updatedCaptor.getValue().status()).isEqualTo(PaymentStatus.PENDING);
    }

    // --- txid regeneration --------------------------------------------------

    @Test
    void duplicate_txid_regenerates_within_bounded_attempts() {
        when(idempotencyStore.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(txidGenerator.generate()).thenReturn(TXID, TXID2);
        doThrow(new DuplicatePaymentTxidException(TXID))
                .doNothing()
                .when(paymentRepo).save(any());        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(TXID2, PSP_EXPIRES, "E2E-1", "br"));
        when(paymentRepo.findByTxid(TXID2))
                .thenReturn(Optional.of(Payment.create(TXID2, MERCHANT, AMOUNT, "x", PSP_EXPIRES, NOW)));
        when(paymentRepo.updateIfVersionMatches(any(), anyInt())).thenReturn(true);

        Output out = useCase.execute(input());

        assertThat(out.txid()).isEqualTo(TXID2);
        verify(paymentRepo, times(2)).save(any());
    }

    // --- helpers -----------------------------------------------------------

    private IdempotencyRecord completedRecord() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txid", TXID.value());
        body.put("status", "PENDING");
        body.put("amount", 10_000L);
        body.put("currency", "BRL");
        body.put("expiresAt", PSP_EXPIRES.toString());
        body.put("brcode", "brcode-for-replay");
        body.put("expiresIn", "PT30M");
        return new IdempotencyRecord(MERCHANT, "idem-key", ENDPOINT, FINGERPRINT_SAME,
                "COMPLETED", TXID.value(), 201, body);
    }

    /** TransactionTemplate over a manager that runs the callback without a real transaction. */
    static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override
        public void commit(TransactionStatus status) {
            ((SimpleTransactionStatus) status).setCompleted();
        }
        @Override
        public void rollback(TransactionStatus status) {
            ((SimpleTransactionStatus) status).setCompleted();
        }
    }
}

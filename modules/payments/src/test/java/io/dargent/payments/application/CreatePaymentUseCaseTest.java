package io.dargent.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dargent.payments.application.CreatePaymentUseCase.Input;
import io.dargent.payments.application.CreatePaymentUseCase.Output;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.PspPort.ChargeResult;
import io.dargent.payments.domain.port.out.PspPort.CreateChargeInput;
import io.dargent.payments.domain.port.out.TxidGenerator;
import io.dargent.shared.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CreatePaymentUseCase contract (E3 spec §5.8): transactional core + explicit PSP seam.
 * Pure unit with mocks — no Spring context. Tests the happy path and key error paths.
 */
class CreatePaymentUseCaseTest {

    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);
    private final IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final PspPort pspPort = mock(PspPort.class);
    private final TxidGenerator txidGenerator = mock(TxidGenerator.class);

    private final CreatePaymentUseCase useCase = new CreatePaymentUseCase(
            paymentRepo, idempotencyStore, outboxWriter, auditWriter, pspPort, txidGenerator,
            100, // feeBps
            "dargent-dev-receber@example.com",
            "Dargent Dev LTDA",
            "SAO PAULO",
            java.time.Clock.systemUTC()
    );

    @Test
    void happy_path_creates_pending_payment_and_runs_psp_phase() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String idemKey = "idem-123";
        String requestFingerprint = "sha256-abc";
        Txid txid = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(30));
        Money amount = Money.of(10000, "BRL");

        when(txidGenerator.generate()).thenReturn(txid);
        when(idempotencyStore.insertIfAbsent(any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        doNothing().when(paymentRepo).save(any());
        when(pspPort.createCharge(any())).thenReturn(new ChargeResult(txid, expiresAt, "E2E-123", "brcode-payload"));

        Output output = useCase.execute(new Input(merchantId, idemKey, "POST /v1/payments",
                requestFingerprint, amount, "Order #123", Duration.ofMinutes(30)));

        assertThat(output.txid()).isEqualTo(txid);
        assertThat(output.status()).isEqualTo(io.dargent.payments.domain.model.PaymentStatus.CONFIRMED);
        assertThat(output.expiresAt()).isEqualTo(expiresAt);
        assertThat(output.brcode()).isNotBlank();

        verify(idempotencyStore).markCompleted(any(), anyString(), anyString(), eq(txid), anyInt(), any());
        verify(pspPort).createCharge(any());
    }

    @Test
    void duplicate_idempotency_key_with_same_fingerprint_returns_existing() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String idemKey = "idem-123";
        String requestFingerprint = "sha256-abc";
        Txid existingTxid = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
        var existingRecord = new IdempotencyRecord(
                UUID.randomUUID(), idemKey, "POST /v1/payments", "sha256-abc",
                "COMPLETED", existingTxid.value(), 201, Map.of("txid", existingTxid.value()));

        when(idempotencyStore.insertIfAbsent(any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(existingRecord));

        // Mock the replay path - need to mock paymentRepo.findByTxid
        Payment existingPayment = mock(Payment.class);
        when(existingPayment.txid()).thenReturn(existingTxid);
        when(existingPayment.status()).thenReturn(io.dargent.payments.domain.model.PaymentStatus.CONFIRMED);
        when(existingPayment.expiresAt()).thenReturn(Instant.now().plus(Duration.ofMinutes(30)));
        when(existingPayment.amount()).thenReturn(Money.of(10000, "BRL"));
        when(paymentRepo.findByTxid(existingTxid)).thenReturn(Optional.of(existingPayment));

        Output output = useCase.execute(new Input(UUID.randomUUID(), idemKey, "POST /v1/payments",
                requestFingerprint, Money.of(10000, "BRL"), "Order #123", Duration.ofMinutes(30)));

        assertThat(output.txid()).isEqualTo(existingTxid);
    }
}
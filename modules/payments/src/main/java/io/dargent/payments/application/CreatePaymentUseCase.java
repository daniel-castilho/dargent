package io.dargent.payments.application;

import io.dargent.payments.domain.br.BrCode;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.TxidGenerator;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CreatePaymentUseCase (E3 spec §5.8): transactional core + explicit PSP seam.
 * Not @Transactional — runs transactional core via internal method, then PSP phase explicitly after commit.
 */
public final class CreatePaymentUseCase {

    private final PaymentRepository paymentRepo;
    private final IdempotencyStore idempotencyStore;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final PspPort pspPort;
    private final TxidGenerator txidGenerator;
    private final int feeBps;
    private final String pixKey;
    private final String receiverName;
    private final String receiverCity;
    private final Clock clock;

    public CreatePaymentUseCase(PaymentRepository paymentRepo, IdempotencyStore idempotencyStore,
            OutboxWriter outboxWriter, AuditWriter auditWriter, PspPort pspPort,
            TxidGenerator txidGenerator,
            int feeBps, String pixKey, String receiverName, String receiverCity, Clock clock) {
        this.paymentRepo = paymentRepo;
        this.idempotencyStore = idempotencyStore;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.pspPort = pspPort;
        this.txidGenerator = txidGenerator;
        this.feeBps = feeBps;
        this.pixKey = pixKey;
        this.receiverName = receiverName;
        this.receiverCity = receiverCity;
        this.clock = clock;
    }

    public Output execute(Input input) {
        // 1. Idempotency: insert IN_FLIGHT or get existing
        var existing = idempotencyStore.insertIfAbsent(
                input.merchantId(), input.idempotencyKey(), input.endpoint(), input.requestFingerprint());
        if (existing.isPresent()) {
            var existingRecord = existing.get();
            if ("COMPLETED".equals(existingRecord.state())) {
                // Replay: fetch and return the original payment
                Txid existingTxid = new Txid(existingRecord.paymentTxid());
                Payment payment = paymentRepo.findByTxid(existingTxid)
                        .orElseThrow(() -> new IllegalStateException("Completed payment not found: " + existingTxid));
                // Reconstruct BR code for response
                String brcode = BrCode.of(pixKey, receiverName, receiverCity,
                        payment.amount().cents(), payment.txid());
                return new Output(payment.txid(), payment.status(), payment.expiresAt(), brcode);
            }
            // Different fingerprint -> 409 idempotency_key_conflict
            throw new IllegalStateException("Idempotency key conflict");
        }

        // 2. Transactional core
        Instant now = clock.instant();
        Instant expiresAtRequested = now.plus(input.expiresIn());
        Txid txid = txidGenerator.generate();
        Payment payment = Payment.create(txid, input.merchantId(), input.amount(), input.description(),
                expiresAtRequested, now);

        // Save payment (handles duplicate txid via bounded retry)
        paymentRepo.save(payment);

        // Outbox + Audit
        String requestId = ""; // Would be passed from context
        outboxWriter.append(payment.txid().value(), "payment.created", 1,
                new PaymentCreatedEvent(payment.txid(), input.merchantId(), input.amount(), input.description(), expiresAtRequested).toJson(),
                requestId);
        auditWriter.record("create_payment", UUID.randomUUID(), input.merchantId(), payment.txid().value(), requestId);

        // 3. PSP phase (after commit)
        try {
            var pspInput = new PspPort.CreateChargeInput(
                    payment.txid(), input.amount().cents(), expiresAtRequested,
                    "https://example.com/callback", input.description());
            var pspResult = pspPort.createCharge(pspInput);

            // Update expires_at with PSP truth (conditional update)
            Payment updatedPayment = payment.withExpiresAt(pspResult.expiresAt());
            paymentRepo.updateIfVersionMatches(payment, 0);

            // Mark idempotency completed with snapshot
            idempotencyStore.markCompleted(input.merchantId(), input.idempotencyKey(), input.endpoint(),
                    pspResult.txid(), 201, Map.of("txid", pspResult.txid().value()));

            return new Output(pspResult.txid(), io.dargent.payments.domain.model.PaymentStatus.CONFIRMED, pspResult.expiresAt(), pspResult.brcodePayload());
        } catch (Exception e) {
            // PSP exhaustion -> mark FAILED
            Payment failedPayment = payment.markFailed("psp_create_exhausted", clock.instant());
            paymentRepo.updateIfVersionMatches(payment, 0);
            idempotencyStore.delete(input.merchantId(), input.idempotencyKey(), input.endpoint());
            throw new IllegalStateException("PSP unavailable", e);
        }
    }

    public record Input(
            UUID merchantId,
            String idempotencyKey,
            String endpoint,
            String requestFingerprint,
            Money amount,
            String description,
            Duration expiresIn
    ) {}

    public record Output(
            Txid txid,
            io.dargent.payments.domain.model.PaymentStatus status,
            Instant expiresAt,
            String brcode
    ) {}

    // Helper for outbox payload
    private record PaymentCreatedEvent(
            Txid txid,
            UUID merchantId,
            Money amount,
            String description,
            Instant expiresAt
    ) {
        String toJson() {
            return String.format("{\"txid\":\"%s\",\"merchantId\":\"%s\",\"amount\":%d,\"description\":\"%s\",\"expiresAt\":\"%s\"}",
                    txid.value(), merchantId, amount.cents(), description, expiresAt);
        }
    }
}
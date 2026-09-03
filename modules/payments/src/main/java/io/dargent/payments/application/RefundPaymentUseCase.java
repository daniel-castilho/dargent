package io.dargent.payments.application;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.exception.InvalidTransitionException;
import io.dargent.payments.domain.exception.RefundExceedsRemainingException;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.dargent.payments.domain.port.out.MerchantBalancePort;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RefundPaymentUseCase (E8 spec §3): processes refunds with proportional fee reversal (D8),
 * balance guard, and conditional payment state transitions. Idempotent via Idempotency-Key.
 * <p>
 * One transaction (D17): lock → validate remainder → D8 fee reversal → Payment.refund
 * → insert refunds row → bump version → outbox refund.created.
 * <p>
 * Balance guard (pre-check, best-effort): MerchantBalancePort.available(merchantId) ≥ net drain.
 * Port failure → fail-closed 409 {@code balance_unavailable} (adjudicated).
 * Ledger's conditional drain remains final arbitration (spec §5).
 */
public final class RefundPaymentUseCase {

    private final PaymentRepository paymentRepo;
    private final IdempotencyStore idempotencyStore;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final MerchantBalancePort balancePort;
    private final EventEnvelopeFactory envelopeFactory;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public RefundPaymentUseCase(PaymentRepository paymentRepo, IdempotencyStore idempotencyStore,
            OutboxWriter outboxWriter, AuditWriter auditWriter, MerchantBalancePort balancePort,
            EventEnvelopeFactory envelopeFactory, TransactionTemplate txTemplate, Clock clock) {
        this.paymentRepo = paymentRepo;
        this.idempotencyStore = idempotencyStore;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.balancePort = balancePort;
        this.envelopeFactory = envelopeFactory;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * Executes a refund command. Idempotent: same key + same body → 201 replay;
     * different body → 409 idempotency_key_conflict; in-flight → 425.
     *
     * @param input refund input (txid, amount, idempotency key, etc.)
     * @return refund output (id, txid, amount, feeReversal, net, status, createdAt)
     */
    public Output execute(Input input) {
        Instant now = clock.instant();

        // 1. Core transaction (atomic: idempotency IN_FLIGHT → lock payment → validate → refund → outbox → audit)
        CoreOutcome core = txTemplate.execute(status -> runCore(input, now));
        if (core.existing() != null) {
            return handleExisting(core.existing(), input);
        }
        RefundResult result = core.result();

        return new Output(result.refundId(), result.txid(), result.amount(), result.feeReversal(),
                result.net(), "SUCCEEDED", result.createdAt());
    }

    // ------------------------------------------------------------------ core

    private CoreOutcome runCore(Input input, Instant now) {
        var existing = idempotencyStore.insertIfAbsent(input.merchantId(), input.idempotencyKey(),
                input.endpoint(), input.requestFingerprint());
        if (existing.isPresent()) {
            return CoreOutcome.existing(existing.get());
        }

        // Lock the payment row (pessimistic FOR UPDATE)
        var paymentOpt = paymentRepo.findByTxidForUpdate(input.txid());
        if (paymentOpt.isEmpty()) {
            throw new PaymentNotFoundException(input.txid());
        }
        Payment payment = paymentOpt.get();

        // Status gate: CONFIRMED or PARTIALLY_REFUNDED only
        if (payment.status() != PaymentStatus.CONFIRMED
                && payment.status() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new InvalidStateException(input.txid(), payment.status().name());
        }

        // Remainder gate: Σ refunds + this ≤ amount
        Money refundAmount = input.amount();
        long remainingCents = payment.amount().cents() - payment.refunded().cents();
        if (refundAmount.cents() > remainingCents) {
            throw new RefundExceedsRemainingException(payment.txid(), remainingCents, refundAmount.cents());
        }

        // Fee reversal (D8): floor(fee × refund / amount)
        var feeBreakdown = new io.dargent.payments.domain.model.FeeBreakdown(
                payment.amount(), payment.fee(), payment.net());
        long feeReversalCents = io.dargent.payments.domain.model.FeeBreakdown.feeReversal(
                refundAmount.cents(), payment.fee().cents(), payment.amount().cents());
        long netCents = refundAmount.cents() - feeReversalCents;

        // Balance guard: pre-check, best-effort, fail-closed
        try {
            long available = balancePort.available(input.merchantId());
            if (available < refundAmount.cents() - feeReversalCents) {
                throw new InsufficientMerchantBalanceException(payment.txid().value(),
                        available, refundAmount.cents() - feeReversalCents);
            }
        } catch (InsufficientMerchantBalanceException e) {
            throw e;
        } catch (RuntimeException e) {
            // Port down or other error → fail-closed
            throw new BalanceUnavailableException(payment.txid().value());
        }

        // Capture version BEFORE the refund transition
        int versionBeforeRefund = payment.version();

        // Perform the refund in the domain (bumps version via transition)
        Instant refundWhen = clock.instant();
        payment.refund(
                Money.of(refundAmount.cents(), "BRL"),
                Money.of(feeReversalCents, "BRL"),
                clock.instant());

        // Insert refund record
        String refundId = UUID.randomUUID().toString();
        long feeReversalCents2 = feeReversalCents(payment, refundAmount);
        long netCents2 = refundAmount.cents() - feeReversalCents2;
        paymentRepo.insertRefund(payment.id(), payment.txid().value(),
                input.amount().cents(), feeReversalCents2, netCents2, input.requestId());

        // Conditional UPDATE on payment (version guard: version before refund)
        if (!paymentRepo.updateIfVersionMatches(payment, payment.version() - 1)) {
            throw new OptimisticLockException(payment.txid().value());
        }

        // Outbox: refund.created
        appendRefundOutbox(payment, refundAmount, feeReversalCents2, clock.instant());

        // Audit
        auditWriter.record("create_refund", input.apiKeyId(), input.merchantId(),
                payment.txid().value(), input.requestId());

        return CoreOutcome.created(new RefundResult(
                UUID.randomUUID().toString(), payment.txid().value(),
                refundAmount.cents(), feeReversalCents(payment, input.amount()),
                netCents2, clock.instant()));
    }

    private long feeReversalCents(Payment payment, Money refundAmount) {
        return io.dargent.payments.domain.model.FeeBreakdown.feeReversal(
                refundAmount.cents(), payment.fee().cents(), payment.amount().cents());
    }

    private void appendRefundOutbox(Payment payment, Money refundAmount, long feeReversalCents, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("amount", refundAmount.cents());
        payload.put("feeRefund", feeReversalCents);
        payload.put("netRefund", refundAmount.cents() - feeReversalCents(payment, refundAmount));
        payload.put("refundId", UUID.randomUUID().toString());
        payload.put("txid", payment.txid().value());
        String envelope = envelopeFactory.envelope("refund.created", 1, payment.txid().value(),
                payment.merchantId(), "refund-request-id", payload, now);
        outboxWriter.append(payment.txid().value(), "refund.created", 1, envelope, "refund-request-id");
    }

    // ------------------------------------------------------------- idempotency

    private Output handleExisting(IdempotencyRecord rec, Input input) {
        boolean sameFingerprint = rec.requestFingerprint().equals(input.requestFingerprint());
        if (!sameFingerprint) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency key conflict for key " + input.idempotencyKey());
        }
        if ("COMPLETED".equals(rec.state())) {
            return replay(rec);
        }
        throw new IdempotencyKeyInFlightException(
                "Idempotency key in flight for key " + input.idempotencyKey());
    }

    private Output replay(IdempotencyRecord rec) {
        Map<String, Object> body = rec.responseBody();
        return new Output(
                String.valueOf(body.get("id")),
                String.valueOf(body.get("payment")),
                Long.parseLong(String.valueOf(body.get("amount"))),
                Long.parseLong(String.valueOf(body.get("feeReversal"))),
                Long.parseLong(String.valueOf(body.get("net"))),
                String.valueOf(body.get("status")),
                Instant.parse(String.valueOf(body.get("createdAt"))));
    }

    // ----------------------------------------------------------------- models

    public record Input(
            String txid,
            UUID merchantId,
            UUID apiKeyId,
            String idempotencyKey,
            String endpoint,
            String requestFingerprint,
            String requestId,
            io.dargent.shared.money.Money amount
    ) {}

    public record Output(
            String id,
            String payment,
            long amount,
            long feeReversal,
            long net,
            String status,
            Instant createdAt
    ) {}

    private record RefundResult(
            String refundId,
            String txid,
            long amount,
            long feeReversal,
            long net,
            Instant createdAt
    ) {}

    private record CoreOutcome(RefundResult result, IdempotencyRecord existing) {
        static CoreOutcome created(RefundResult result) {
            return new CoreOutcome(result, null);
        }
        static CoreOutcome existing(IdempotencyRecord existing) {
            return new CoreOutcome(null, existing);
        }
    }

    // ------------------------------------------------------------- exceptions

    public static final class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(String txid) {
            super("Payment not found: " + txid);
        }
    }

    public static final class InvalidStateException extends RuntimeException {
        public InvalidStateException(String txid, String status) {
            super("Payment " + txid + " cannot be refunded in state " + status);
        }
    }

    public static final class InsufficientMerchantBalanceException extends RuntimeException {
        public InsufficientMerchantBalanceException(String txid, long available, long required) {
            super("Insufficient merchant balance for refund " + txid + ": available=" + available + " required=" + required);
        }
    }

    public static final class BalanceUnavailableException extends RuntimeException {
        public BalanceUnavailableException(String txid) {
            super("Balance service unavailable for refund " + txid);
        }
    }

    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String txid) {
            super("Optimistic lock failure on refund " + txid);
        }
    }
}
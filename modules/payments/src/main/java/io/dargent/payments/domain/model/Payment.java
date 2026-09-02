package io.dargent.payments.domain.model;

import io.dargent.payments.domain.exception.InvalidTransitionException;
import io.dargent.payments.domain.exception.RefundExceedsRemainingException;
import io.dargent.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The payment aggregate — the single authority on what may happen to a charge
 * (design.md §4.1, spec §5.2). Lifecycle is guarded inside the entity: every
 * illegal transition is impossible, zero setters, no {@code Instant.now()} —
 * all time arrives as parameters. The persistence seam re-imposes the same
 * guard with the conditional version guard (the database arbitrates races).
 *
 * <p>{@link #restore} exists for the persistence adapter ONLY: it rebuilds an
 * aggregate from storage without raising events or re-validating the timeline;
 * all other construction goes through {@link #create}.
 */
public final class Payment {

    private static final String BRL = "BRL";

    private final UUID id;
    private final Txid txid;
    private final UUID merchantId;
    private final Money amount;
    private final String description;
    private final Instant expiresAt;
    private final Instant createdAt;

    private PaymentStatus status;
    private int version;
    private EndToEndId endToEndId;
    private Money fee;
    private Money net;
    private boolean lateConfirmation;
    private Instant confirmedAt;
    private Money refunded;

    private final List<PaymentEvent> domainEvents = new ArrayList<>();

    private Payment(UUID id, Txid txid, UUID merchantId, Money amount, String description, Instant expiresAt,
                    Instant createdAt) {
        this.id = id;
        this.txid = txid;
        this.merchantId = merchantId;
        this.amount = amount;
        this.description = description;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** Birth of a charge: {@code PENDING}, version 0, raises {@link PaymentCreated}. */
    public static Payment create(Txid txid, UUID merchantId, Money amount, String description,
                                 Instant expiresAt, Instant now) {
        if (txid == null || merchantId == null || amount == null || expiresAt == null || now == null) {
            throw new IllegalArgumentException("all creation parameters are required");
        }
        if (!BRL.equals(amount.currency())) {
            throw new IllegalArgumentException("BRL-only in v1, got: " + amount.currency());
        }
        if (amount.cents() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount.cents());
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after creation time");
        }
        var payment = new Payment(UUID.randomUUID(), txid, merchantId, amount, description, expiresAt, now);
        payment.status = PaymentStatus.PENDING;
        payment.version = 0;
        payment.refunded = Money.zero(BRL);
        payment.raise(new PaymentCreated(txid, merchantId, amount, description, expiresAt, now));
        return payment;
    }

    /**
     * Rebuilds an aggregate from the persistence seam (no events raised, no
     * timeline validation, version preserved). Adapter use only — see class javadoc.
     */
    public static Payment restore(UUID id, Txid txid, UUID merchantId, Money amount, String description,
                                  Instant expiresAt, Instant createdAt, PaymentStatus status, int version,
                                  EndToEndId endToEndId, Money fee, Money net, boolean lateConfirmation,
                                  Instant confirmedAt, long refundedCents) {
        var payment = new Payment(id, txid, merchantId, amount, description, expiresAt, createdAt);
        payment.status = status;
        payment.version = version;
        payment.endToEndId = endToEndId;
        payment.fee = fee;
        payment.net = net;
        payment.lateConfirmation = lateConfirmation;
        payment.confirmedAt = confirmedAt;
        payment.refunded = Money.of(refundedCents, BRL);
        validateRestored(payment);
        return payment;
    }

    /**
     * DEBT-1 rejection: {@link #restore} is the hydration seam for the persistence adapter.
     * The aggregate is the single authority on what is legal (AGENTS §3.1, §5.1), so the seam
     * re-validates the invariants {@link #create}/{@link #confirm} guarantee, never materializing
     * a silent invalid aggregate from a lying adapter.
     */
    private static void validateRestored(Payment p) {
        if (p.amount == null) {
            throw new IllegalArgumentException("restored payment requires an amount");
        }
        if (!BRL.equals(p.amount.currency())) {
            throw new IllegalArgumentException("BRL-only, got: " + p.amount.currency());
        }
        if (p.amount.cents() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + p.amount.cents());
        }
        if (p.expiresAt == null || p.createdAt == null || !p.expiresAt.isAfter(p.createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (p.refunded == null || p.refunded.cents() < 0) {
            throw new IllegalArgumentException("refunded must be non-negative");
        }
        boolean confirmedFamily = p.status == PaymentStatus.CONFIRMED
                || p.status == PaymentStatus.PARTIALLY_REFUNDED
                || p.status == PaymentStatus.REFUNDED;
        if (confirmedFamily) {
            if (p.fee == null || p.net == null || p.confirmedAt == null) {
                throw new IllegalArgumentException(
                        p.status.name() + " snapshot must carry fee, net and confirmedAt");
            }
        } else if (p.fee != null || p.net != null || p.confirmedAt != null
                || (p.status != PaymentStatus.PENDING && p.status != PaymentStatus.EXPIRED
                && p.status != PaymentStatus.FAILED)) {
            throw new IllegalArgumentException("unknown restored status: " + p.status);
        }
    }

    public Payment confirm(EndToEndId endToEndId, FeeBreakdown breakdown, Instant when) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.EXPIRED) {
            throw new InvalidTransitionException(txid, status, PaymentStatus.CONFIRMED);
        }
        if (endToEndId == null || breakdown == null || when == null) {
            throw new IllegalArgumentException("endToEndId, breakdown and when are required");
        }
        if (!amount.equals(breakdown.amount())) {
            throw new IllegalArgumentException(
                    "breakdown amount must equal the payment amount: " + breakdown.amount() + " vs " + amount);
        }
        this.endToEndId = endToEndId;
        this.fee = breakdown.fee();
        this.net = breakdown.net();
        this.confirmedAt = when;
        boolean late = status == PaymentStatus.EXPIRED; // resurrection (D6)
        this.lateConfirmation = late;
        transition(PaymentStatus.CONFIRMED,
                new PaymentConfirmed(txid, endToEndId, amount, fee, net, late, when));
        return this;
    }

    public Payment expire(Instant when) {
        if (status != PaymentStatus.PENDING) {
            throw new InvalidTransitionException(txid, status, PaymentStatus.EXPIRED);
        }
        if (when == null) {
            throw new IllegalArgumentException("when is required");
        }
        if (!when.isAfter(expiresAt)) {
            throw new IllegalArgumentException("expiration must happen after the payment deadline");
        }
        transition(PaymentStatus.EXPIRED, new PaymentExpired(txid, when));
        return this;
    }

    public Payment markFailed(String reason, Instant when) {
        if (status != PaymentStatus.PENDING) {
            throw new InvalidTransitionException(txid, status, PaymentStatus.FAILED);
        }
        if (reason == null || when == null) {
            throw new IllegalArgumentException("reason and when are required");
        }
        transition(PaymentStatus.FAILED, new PaymentFailed(txid, reason, when));
        return this;
    }

    public Payment refund(Money refundAmount, Money feeReversal, Instant when) {
        long remainingCents = remaining().cents();
        if (status != PaymentStatus.CONFIRMED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            PaymentStatus prospective = refundAmount.cents() == remainingCents
                    ? PaymentStatus.REFUNDED
                    : PaymentStatus.PARTIALLY_REFUNDED;
            throw new InvalidTransitionException(txid, status, prospective);
        }
        if (refundAmount == null || feeReversal == null || when == null) {
            throw new IllegalArgumentException("refundAmount, feeReversal and when are required");
        }
        if (refundAmount.cents() <= 0) {
            throw new IllegalArgumentException("refund must be positive: " + refundAmount.cents());
        }
        if (refundAmount.cents() > remainingCents) {
            throw new RefundExceedsRemainingException(txid, remainingCents, refundAmount.cents());
        }
        Money netReversal = refundAmount.minus(feeReversal);
        this.refunded = refunded.plus(refundAmount);
        PaymentStatus to = refundAmount.cents() == remainingCents
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        transition(to, new RefundCreated(txid, refundAmount, feeReversal, netReversal, when));
        return this;
    }

    /**
     * Drains the raised events (idempotent: empty on the second call). Order is
     * the raised order. E6 maps these to the outbox envelope; the relay must read
     * this AFTER the transaction commits.
     */
    public List<PaymentEvent> domainEvents() {
        var drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    // ---- read model for the seam ----

    /** Returns a new Payment with updated expiresAt (for PSP truth update). */
    public Payment withExpiresAt(Instant newExpiresAt) {
        if (newExpiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        var copy = new Payment(id, txid, merchantId, amount, description, newExpiresAt, createdAt);
        copy.status = this.status;
        copy.version = this.version;
        copy.endToEndId = this.endToEndId;
        copy.fee = this.fee;
        copy.net = this.net;
        copy.lateConfirmation = this.lateConfirmation;
        copy.confirmedAt = this.confirmedAt;
        copy.refunded = this.refunded;
        return copy;
    }

    public UUID id() {
        return id;
    }

    public Txid txid() {
        return txid;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public String description() {
        return description;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public PaymentStatus status() {
        return status;
    }

    public int version() {
        return version;
    }

    public EndToEndId endToEndId() {
        return endToEndId;
    }

    public Money fee() {
        return fee;
    }

    public Money net() {
        return net;
    }

    public boolean lateConfirmation() {
        return lateConfirmation;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Money refunded() {
        return refunded;
    }

    /** {@code amount − Σ refunded}; the ledger's balance check is E8. */
    public Money remaining() {
        return amount.minus(refunded);
    }

    private void transition(PaymentStatus to, PaymentEvent event) {
        this.status = to;
        this.version += 1;
        raise(event);
    }

    /**
     * Reflects the version the persistence layer assigned after a successful
     * guarded update (the DB, not the domain, is the version authority — D6).
     * Adapter-seam use only.
     */
    public void markPersistedVersion(int version) {
        this.version = version;
    }

    private void raise(PaymentEvent event) {
        domainEvents.add(event);
    }
}
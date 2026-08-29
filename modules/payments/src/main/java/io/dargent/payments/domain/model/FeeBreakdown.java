package io.dargent.payments.domain.model;

import io.dargent.shared.money.Money;

/**
 * Fee composition of a payment (design.md §5.3, decision D7) — always travels with
 * events so consumers never recompute it. All currency is BRL in v1.
 *
 * <p>Formulas (spec §7), all integer math:
 * {@code fee = floor(amount × bps / 10 000)}, {@code net = amount − fee},
 * {@code reversal = floor(originalFee × refund / originalAmount)}.
 * Rounding is <strong>down</strong> everywhere — merchant-favorable on net, and on
 * refunds the floor residue stays with the platform (D8). Invariants are property-tested
 * in {@code FeeBreakdownTest}.
 */
public record FeeBreakdown(Money amount, Money fee, Money net) {

    public FeeBreakdown {
        if (amount == null || fee == null || net == null) {
            throw new IllegalArgumentException("amount, fee and net are required");
        }
        requireSameCurrency(amount, fee, net);
        if (amount.cents() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount.cents());
        }
        if (fee.cents() < 0 || fee.cents() > amount.cents()) {
            throw new IllegalArgumentException(
                    "fee must be within [0, amount]: " + fee.cents() + "/" + amount.cents());
        }
        if (fee.plus(net).compareTo(amount) != 0) {
            throw new IllegalArgumentException(
                    "fee + net must equal amount: " + fee.cents() + " + " + net.cents() + " != " + amount.cents());
        }
    }

    public static FeeBreakdown of(long amountCents, BpsRate bps) {
        long fee = amountCents * bps.value() / 10_000L;
        return new FeeBreakdown(
                Money.of(amountCents, BRL),
                Money.of(fee, BRL),
                Money.of(amountCents - fee, BRL));
    }

    /** Proportional fee reversal for a refund (spec §7): {@code floor(fee × refund / amount)}. */
    public Money feeReversalFor(long refundCents) {
        if (refundCents < 0) {
            throw new IllegalArgumentException("refund must not be negative: " + refundCents);
        }
        return Money.of(feeReversal(refundCents, fee.cents(), amount.cents()), BRL);
    }

    /** Static form of the reversal formula — pure, index-free, unit-testable. */
    public static long feeReversal(long refundCents, long originalFeeCents, long originalAmountCents) {
        return originalFeeCents * refundCents / originalAmountCents;
    }

    private static void requireSameCurrency(Money... values) {
        for (Money value : values) {
            if (!BRL.equals(value.currency())) {
                throw new IllegalArgumentException("BRL-only in v1, got: " + value.currency());
            }
        }
    }

    private static final String BRL = "BRL";
}
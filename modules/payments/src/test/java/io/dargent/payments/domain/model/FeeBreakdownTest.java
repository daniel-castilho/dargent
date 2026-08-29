package io.dargent.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class FeeBreakdownTest {

    // ---- unit edges (spec §7) ----

    @Test
    void computes_fee_and_net_for_a_rate() {
        var b = FeeBreakdown.of(10_000, new BpsRate(100));
        assertThat(b.amount().cents()).isEqualTo(10_000);
        assertThat(b.fee().cents()).isEqualTo(100);
        assertThat(b.net().cents()).isEqualTo(9_900);
    }

    @Test
    void fee_rounds_down_merchant_favorable() {
        var b = FeeBreakdown.of(9_999, new BpsRate(100));
        assertThat(b.fee().cents()).isEqualTo(99);
        assertThat(b.net().cents()).isEqualTo(9_900);
        assertThat(b.fee().cents() + b.net().cents()).isEqualTo(9_999);
    }

    @Test
    void zero_bps_yields_zero_fee_and_full_net() {
        var b = FeeBreakdown.of(123, new BpsRate(0));
        assertThat(b.fee().cents()).isZero();
        assertThat(b.net().cents()).isEqualTo(123);
    }

    @Test
    void full_rate_keeps_the_entire_amount_as_fee() {
        var b = FeeBreakdown.of(5_000, new BpsRate(10_000));
        assertThat(b.fee().cents()).isEqualTo(5_000);
        assertThat(b.net().cents()).isZero();
    }

    @Test
    void rejects_non_positive_amounts() {
        assertThatThrownBy(() -> FeeBreakdown.of(0, new BpsRate(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeBreakdown.of(-5, new BpsRate(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fee_reversal_is_proportional_with_floor() {
        var b = FeeBreakdown.of(10_000, new BpsRate(100));
        assertThat(b.feeReversalFor(4_000).cents()).isEqualTo(40);
        assertThat(b.feeReversalFor(3_333).cents()).isEqualTo(33);
    }

    @Test
    void full_refund_reversal_returns_the_original_fee() {
        var b = FeeBreakdown.of(10_000, new BpsRate(100));
        assertThat(b.feeReversalFor(10_000).cents()).isEqualTo(100);
    }

    // ---- jqwik properties (spec §7: rounding down, fee+net==amount) ----

    @Property
    void fee_plus_net_always_equals_amount(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 10_000_000) long amountCents,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10_000) int bps) {
        var b = FeeBreakdown.of(amountCents, new BpsRate(bps));
        assertThat(b.fee().cents() + b.net().cents()).isEqualTo(amountCents);
    }

    @Property
    void fee_stays_between_zero_and_amount(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 10_000_000) long amountCents,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10_000) int bps) {
        var b = FeeBreakdown.of(amountCents, new BpsRate(bps));
        assertThat(b.fee().cents()).isBetween(0L, amountCents);
        assertThat(b.net().cents()).isBetween(0L, amountCents);
    }

    @Property
    void reversal_stays_between_zero_and_original_fee(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 10_000_000) long amountCents,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10_000) int bps,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 10_000_000) long refundCents) {
        var b = FeeBreakdown.of(amountCents, new BpsRate(bps));
        Assume.that(refundCents <= amountCents);
        assertThat(b.feeReversalFor(refundCents).cents()).isBetween(0L, b.fee().cents());
    }

    @Property
    void partial_reversals_never_exceed_the_fee_when_refunds_are_bounded(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 10_000_000) long amountCents,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10_000) int bps,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 10_000_000) long r1,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 10_000_000) long r2) {
        var b = FeeBreakdown.of(amountCents, new BpsRate(bps));
        Assume.that(r1 + r2 <= amountCents);
        var reversal1 = b.feeReversalFor(r1).cents();
        var reversal2 = b.feeReversalFor(r2).cents();
        assertThat(reversal1 + reversal2).isLessThanOrEqualTo(b.fee().cents());
    }

    @Property
    void zero_bps_yields_zero_fee_and_full_net_for_any_amount(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 10_000_000) long amountCents) {
        var b = FeeBreakdown.of(amountCents, new BpsRate(0));
        assertThat(b.fee().cents()).isZero();
        assertThat(b.net().cents()).isEqualTo(amountCents);
    }

    @Property
    void full_amount_reversal_always_returns_the_original_fee(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 10_000_000) long amountCents,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10_000) int bps) {
        var b = FeeBreakdown.of(amountCents, new BpsRate(bps));
        assertThat(b.feeReversalFor(amountCents).cents()).isEqualTo(b.fee().cents());
    }
}
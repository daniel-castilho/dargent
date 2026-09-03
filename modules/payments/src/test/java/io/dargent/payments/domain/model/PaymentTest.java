package io.dargent.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dargent.payments.domain.exception.InvalidTransitionException;
import io.dargent.payments.domain.exception.RefundExceedsRemainingException;
import io.dargent.shared.money.Money;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Table-driven suite over spec §5.2: every legal transition asserts the new state,
 * the version bump and the raised event payload; every illegal one asserts the
 * typed exception. "EXPIRED is not terminal" resurrection is pinned explicitly.
 */
class PaymentTest {

    private static final Txid TXID = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(1_800);
    private static final Money AMOUNT = Money.of(10_000, "BRL");
    private static final EndToEndId END_TO_END_ID = new EndToEndId("E00416968202009221504E2345678910");
    private static final FeeBreakdown BREAKDOWN = FeeBreakdown.of(10_000, new BpsRate(100));

    // ---- builders for each source state ----

    private static Payment create() {
        return Payment.create(TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW);
    }

    private static Payment confirmed() {
        return create().confirm(END_TO_END_ID, BREAKDOWN, NOW.plusSeconds(60));
    }

    private static Payment partiallyRefunded() {
        return confirmed()
                .refund(Money.of(4_000, "BRL"), BREAKDOWN.feeReversalFor(4_000), NOW.plusSeconds(120));
    }

    private static Payment expired() {
        return create().expire(NOW.plusSeconds(4_000));
    }

    private static Payment failed() {
        return create().markFailed("psp unavailable", NOW.plusSeconds(10));
    }

    private static Payment refunded() {
        return confirmed()
                .refund(Money.of(10_000, "BRL"), BREAKDOWN.feeReversalFor(10_000), NOW.plusSeconds(120));
    }

    // ---- birth ----

    @Test
    void create_births_a_pending_payment_with_version_zero_created_event_and_empty_remaining() {
        var p = create();
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.version()).isZero();
        assertThat(p.createdAt()).isEqualTo(NOW);
        assertThat(p.refunded().cents()).isZero();
        assertThat(p.confirmedAt()).isNull();
        assertDomainEvent(p, PaymentCreated.class, ev -> {
            assertThat(ev.txid()).isEqualTo(TXID);
            assertThat(ev.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(ev.amount()).isEqualTo(AMOUNT);
            assertThat(ev.description()).isEqualTo("order-1");
            assertThat(ev.expiresAt()).isEqualTo(EXPIRES_AT);
            assertThat(ev.occurredAt()).isEqualTo(NOW);
        });
        assertThat(p.domainEvents()).isEmpty();
    }

    @Test
    void create_rejects_zero_or_negative_amounts() {
        assertThatThrownBy(() -> Payment.create(TXID, MERCHANT_ID, Money.of(0, "BRL"), "o", EXPIRES_AT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.create(TXID, MERCHANT_ID, Money.of(-1, "BRL"), "o", EXPIRES_AT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejects_expiration_at_or_before_creation_time() {
        assertThatThrownBy(() -> Payment.create(TXID, MERCHANT_ID, AMOUNT, "o", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.create(TXID, MERCHANT_ID, AMOUNT, "o", NOW.minusSeconds(1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejects_non_brl_amounts() {
        assertThatThrownBy(() -> Payment.create(TXID, MERCHANT_ID, Money.of(100, "USD"), "o", EXPIRES_AT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- confirm ----

    @Test
    void confirm_from_pending_moves_to_confirmed_late_false_and_sets_fee_math() {
        var p = confirmed();
        assertThat(p.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.endToEndId()).isEqualTo(END_TO_END_ID);
        assertThat(p.fee()).isEqualTo(Money.of(100, "BRL"));
        assertThat(p.net()).isEqualTo(Money.of(9_900, "BRL"));
        assertThat(p.lateConfirmation()).isFalse();
        assertThat(p.confirmedAt()).isEqualTo(NOW.plusSeconds(60));
        assertDomainEvent(p, PaymentConfirmed.class, ev -> {
            assertThat(ev.txid()).isEqualTo(TXID);
            assertThat(ev.endToEndId()).isEqualTo(END_TO_END_ID);
            assertThat(ev.amount()).isEqualTo(AMOUNT);
            assertThat(ev.fee()).isEqualTo(Money.of(100, "BRL"));
            assertThat(ev.net()).isEqualTo(Money.of(9_900, "BRL"));
            assertThat(ev.late()).isFalse();
            assertThat(ev.occurredAt()).isEqualTo(NOW.plusSeconds(60));
        });
    }

    @Test
    void confirm_from_expired_resurrects_with_late_true_and_audit_fields() {
        var p = expired().confirm(END_TO_END_ID, BREAKDOWN, NOW.plusSeconds(7_000));
        assertThat(p.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.lateConfirmation()).isTrue();
        assertThat(p.confirmedAt()).isEqualTo(NOW.plusSeconds(7_000));
        assertDomainEvent(p, PaymentConfirmed.class, ev -> {
            assertThat(ev.late()).isTrue();
            assertThat(ev.occurredAt()).isEqualTo(NOW.plusSeconds(7_000));
        });
    }

    @Test
    void confirm_with_mismatched_breakdown_amount_is_rejected() {
        var p = create();
        assertThatThrownBy(() -> p.confirm(END_TO_END_ID, FeeBreakdown.of(9_999, new BpsRate(100)), NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void confirm_is_illegal_from_confirmed_partially_refunded_refunded_and_failed() {
        assertConfirmIllegalFrom(allStatesExcept(PaymentStatus.PENDING, PaymentStatus.EXPIRED));
    }

    // ---- expire ----

    @Test
    void expire_from_pending_moves_to_expired_after_deadline() {
        var p = expired();
        assertThat(p.status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(p.version()).isEqualTo(1);
        assertDomainEvent(p, PaymentExpired.class, ev -> {
            assertThat(ev.txid()).isEqualTo(TXID);
            assertThat(ev.occurredAt()).isEqualTo(NOW.plusSeconds(4_000));
        });
    }

    @Test
    void expire_at_or_before_deadline_is_rejected() {
        assertThatThrownBy(() -> create().expire(NOW.plusSeconds(1_800)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create().expire(NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expire_is_illegal_from_every_state_but_pending() {
        assertExpireIllegalFrom(allStatesExcept(PaymentStatus.PENDING));
    }

    @Test
    void is_due_for_expiration_true_only_for_pending_past_deadline() {
        assertThat(create().isDueForExpiration(NOW.plusSeconds(1_801))).isTrue();
        assertThat(create().isDueForExpiration(EXPIRES_AT)).isFalse();       // exactly at deadline
        assertThat(create().isDueForExpiration(EXPIRES_AT.minusSeconds(1))).isFalse(); // before
        assertThat(confirmed().isDueForExpiration(NOW.plusSeconds(9_999))).isFalse();   // CONFIRMED never expires
        assertThat(expired().isDueForExpiration(NOW.plusSeconds(9_999))).isFalse();     // already EXPIRED (D6: may resurrect)
        assertThat(failed().isDueForExpiration(NOW.plusSeconds(9_999))).isFalse();
        assertThat(create().isDueForExpiration(null)).isFalse();             // Clock-injected; null now = not due
    }

    // ---- markFailed ----

    @Test
    void mark_failed_from_pending_moves_to_failed_carrying_reason_and_time() {
        var p = failed();
        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.version()).isEqualTo(1);
        assertDomainEvent(p, PaymentFailed.class, ev -> {
            assertThat(ev.txid()).isEqualTo(TXID);
            assertThat(ev.reason()).isEqualTo("psp unavailable");
            assertThat(ev.occurredAt()).isEqualTo(NOW.plusSeconds(10));
        });
    }

    @Test
    void mark_failed_is_illegal_from_every_state_but_pending() {
        assertMarkFailedIllegalFrom(allStatesExcept(PaymentStatus.PENDING));
    }

    // ---- refund ----

    @Test
    void partial_refund_from_confirmed_moves_to_partially_refunded_and_tracks_remaining() {
        var p = partiallyRefunded();
        assertThat(p.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.refunded().cents()).isEqualTo(4_000);
        assertThat(p.remaining().cents()).isEqualTo(6_000);
        assertDomainEvent(p, RefundCreated.class, ev -> {
            assertThat(ev.txid()).isEqualTo(TXID);
            assertThat(ev.refundAmount()).isEqualTo(Money.of(4_000, "BRL"));
            assertThat(ev.feeReversal()).isEqualTo(BREAKDOWN.feeReversalFor(4_000));
            assertThat(ev.netReversal()).isEqualTo(Money.of(3_960, "BRL"));
            assertThat(ev.occurredAt()).isEqualTo(NOW.plusSeconds(120));
        });
    }

    @Test
    void full_refund_from_confirmed_moves_to_terminal_refunded() {
        var p = refunded();
        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.remaining().cents()).isZero();
        assertThat(p.status().isTerminal()).isTrue();
    }

    @Test
    void second_partial_refund_on_partially_refunded_keeps_it_partially_refunded() {
        var p = partiallyRefunded().refund(
                Money.of(3_000, "BRL"), BREAKDOWN.feeReversalFor(3_000), NOW.plusSeconds(240));
        assertThat(p.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(p.remaining().cents()).isEqualTo(3_000);
        assertThat(p.version()).isEqualTo(3);
    }

    @Test
    void zeroing_refund_on_partially_refunded_moves_to_terminal_refunded() {
        var p = partiallyRefunded().refund(
                Money.of(6_000, "BRL"), BREAKDOWN.feeReversalFor(6_000), NOW.plusSeconds(240));
        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.remaining().cents()).isZero();
        assertThat(p.refunded().cents()).isEqualTo(10_000);
        assertThat(p.version()).isEqualTo(3);
    }

    @Test
    void refund_beyond_remaining_throws_refund_exceeds_remaining_with_context() {
        var p = confirmed();
        assertThatThrownBy(() -> p.refund(
                Money.of(10_001, "BRL"), BREAKDOWN.feeReversalFor(10_001), NOW.plusSeconds(120)))
                .isInstanceOf(RefundExceedsRemainingException.class)
                .satisfies(ex -> {
                    var typed = (RefundExceedsRemainingException) ex;
                    assertThat(typed.txid()).isEqualTo(TXID);
                    assertThat(typed.remainingCents()).isEqualTo(10_000);
                    assertThat(typed.requestedCents()).isEqualTo(10_001);
                });
        assertThat(p.status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void refund_of_zero_or_negative_is_rejected() {
        var p = confirmed();
        assertThatThrownBy(() -> p.refund(Money.of(0, "BRL"), BREAKDOWN.feeReversalFor(0), NOW.plusSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.refund(Money.of(-1, "BRL"), BREAKDOWN.feeReversalFor(0), NOW.plusSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refund_is_illegal_from_pending_expired_refunded_and_failed() {
        assertRefundIllegalFrom(PaymentStatus.PENDING, PaymentStatus.EXPIRED, PaymentStatus.REFUNDED, PaymentStatus.FAILED);
    }

    // ---- hydration seam rejecting contract (DEBT-1, AGENTS §8) ----

    @Test
    void restore_rejects_a_confirmed_snapshot_without_fee_or_net_or_confirmed_at() {
        assertThatThrownBy(() -> Payment.restore(
                UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1",
                EXPIRES_AT, NOW, PaymentStatus.CONFIRMED, 1,
                END_TO_END_ID, null, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_rejects_a_snapshot_whose_amount_is_not_positive_brl() {
        assertThatThrownBy(() -> Payment.restore(
                UUID.randomUUID(), TXID, MERCHANT_ID, Money.of(0, "BRL"), "order-1",
                EXPIRES_AT, NOW, PaymentStatus.PENDING, 0,
                null, null, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.restore(
                UUID.randomUUID(), TXID, MERCHANT_ID, Money.of(100, "USD"), "order-1",
                EXPIRES_AT, NOW, PaymentStatus.PENDING, 0,
                null, null, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_rejects_a_snapshot_whose_expiry_predates_creation() {
        assertThatThrownBy(() -> Payment.restore(
                UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1",
                NOW, NOW.plusSeconds(10), PaymentStatus.PENDING, 0,
                null, null, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_round_trips_every_legal_snapshot_without_raising() {
        // The seam must not reject the legitimate hydration of any aggregate a valid
        // lifecycle can produce (PENDING, EXPIRED, FAILED, CONFIRMED, PARTIALLY_REFUNDED, REFUNDED).
        assertThat(Payment.restore(UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW,
                PaymentStatus.PENDING, 0, null, null, null, false, null, 0)).isNotNull();
        assertThat(Payment.restore(UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW,
                PaymentStatus.EXPIRED, 1, null, null, null, false, null, 0)).isNotNull();
        assertThat(Payment.restore(UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW,
                PaymentStatus.FAILED, 1, null, null, null, false, null, 0)).isNotNull();
        assertThat(Payment.restore(UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW,
                PaymentStatus.CONFIRMED, 1, END_TO_END_ID, Money.of(100, "BRL"), Money.of(9_900, "BRL"),
                false, NOW.plusSeconds(60), 0)).isNotNull();
        assertThat(Payment.restore(UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW,
                PaymentStatus.PARTIALLY_REFUNDED, 2, END_TO_END_ID, Money.of(100, "BRL"), Money.of(9_900, "BRL"),
                false, NOW.plusSeconds(60), 4_000)).isNotNull();
        assertThat(Payment.restore(UUID.randomUUID(), TXID, MERCHANT_ID, AMOUNT, "order-1", EXPIRES_AT, NOW,
                PaymentStatus.REFUNDED, 2, END_TO_END_ID, Money.of(100, "BRL"), Money.of(9_900, "BRL"),
                false, NOW.plusSeconds(60), 10_000)).isNotNull();
    }

    // ---- terminal reachability ----

    @Test
    void terminal_states_reject_every_further_transition() {
        for (var terminal : java.util.List.of(refunded(), failed())) {
            var check = terminal;
            assertThatThrownBy(() -> check.confirm(END_TO_END_ID, BREAKDOWN, NOW.plusSeconds(60)))
                    .isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> check.expire(NOW.plusSeconds(4_000)))
                    .isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> check.markFailed("x", NOW.plusSeconds(60)))
                    .isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> check.refund(Money.of(1, "BRL"), BREAKDOWN.feeReversalFor(1), NOW.plusSeconds(60)))
                    .isInstanceOf(InvalidTransitionException.class);
        }
    }

    // ---- illegal-transition helpers (spec §5.2 cells) ----

    private void assertConfirmIllegalFrom(PaymentStatus... sources) {
        for (var source : sources) {
            assertThatThrownBy(() -> build(source).confirm(END_TO_END_ID, BREAKDOWN, NOW.plusSeconds(60)))
                    .isInstanceOf(InvalidTransitionException.class)
                    .satisfies(ex -> assertTransitionContext((InvalidTransitionException) ex, source, PaymentStatus.CONFIRMED));
        }
    }

    private void assertExpireIllegalFrom(PaymentStatus... sources) {
        for (var source : sources) {
            assertThatThrownBy(() -> build(source).expire(NOW.plusSeconds(4_000)))
                    .isInstanceOf(InvalidTransitionException.class)
                    .satisfies(ex -> assertTransitionContext((InvalidTransitionException) ex, source, PaymentStatus.EXPIRED));
        }
    }

    private void assertMarkFailedIllegalFrom(PaymentStatus... sources) {
        for (var source : sources) {
            assertThatThrownBy(() -> build(source).markFailed("x", NOW.plusSeconds(60)))
                    .isInstanceOf(InvalidTransitionException.class)
                    .satisfies(ex -> assertTransitionContext((InvalidTransitionException) ex, source, PaymentStatus.FAILED));
        }
    }

    private void assertRefundIllegalFrom(PaymentStatus... sources) {
        for (var source : sources) {
            assertThatThrownBy(() -> build(source).refund(Money.of(1, "BRL"), BREAKDOWN.feeReversalFor(1), NOW.plusSeconds(60)))
                    .isInstanceOf(InvalidTransitionException.class)
                    .satisfies(ex -> assertTransitionContext((InvalidTransitionException) ex, source, PaymentStatus.PARTIALLY_REFUNDED));
        }
    }

    private static void assertTransitionContext(InvalidTransitionException ex, PaymentStatus from, PaymentStatus to) {
        assertThat(ex.txid()).isEqualTo(TXID);
        assertThat(ex.from()).isEqualTo(from);
        assertThat(ex.to()).isEqualTo(to);
    }

    private static Payment build(PaymentStatus status) {
        return switch (status) {
            case PENDING -> create();
            case CONFIRMED -> confirmed();
            case PARTIALLY_REFUNDED -> partiallyRefunded();
            case REFUNDED -> refunded();
            case EXPIRED -> expired();
            case FAILED -> failed();
        };
    }

    private static PaymentStatus[] allStatesExcept(PaymentStatus... excluded) {
        return java.util.Arrays.stream(PaymentStatus.values())
                .filter(s -> java.util.Arrays.stream(excluded).noneMatch(s::equals))
                .toArray(PaymentStatus[]::new);
    }

    private static <T extends PaymentEvent> void assertDomainEvent(
            Payment payment, Class<T> type, Consumer<T> assertions) {
        var events = payment.domainEvents();
        assertThat(events).last().isInstanceOf(type);
        assertions.accept(type.cast(events.getLast()));
        assertThat(payment.domainEvents()).isEmpty();
    }
}
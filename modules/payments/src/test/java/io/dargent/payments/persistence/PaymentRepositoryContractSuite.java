package io.dargent.payments.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dargent.payments.domain.model.BpsRate;
import io.dargent.payments.domain.model.EndToEndId;
import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.shared.money.Money;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The lost-race contract (spec §6), exercised once for the in-memory fake and
 * again for the JPA adapter against real PostgreSQL 16. Every concrete
 * {@link PaymentRepository} must satisfy this suite. "Events preservation" is
 * interpreted as: the persisted effects of raised events (state fields, version,
 * fee math) survive the save → find round trip.
 */
public abstract class PaymentRepositoryContractSuite {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final EndToEndId E2E = new EndToEndId("E00416968202009221504E2345678910");
    private static final FeeBreakdown BREAKDOWN = FeeBreakdown.of(10_000, new BpsRate(100));

    protected abstract PaymentRepository repository();

    @Test
    void save_and_find_round_trip_preserves_all_domain_fields() {
        var txid = newTxid();
        var p = newPayment(txid).confirm(E2E, BREAKDOWN, NOW.plusSeconds(60));
        repository().save(p);

        var found = repository().findByTxid(txid).orElseThrow();
        assertThat(found.txid()).isEqualTo(txid);
        assertThat(found.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(found.amount()).isEqualTo(Money.of(10_000, "BRL"));
        assertThat(found.description()).isEqualTo("order-1");
        assertThat(found.expiresAt()).isEqualTo(NOW.plusSeconds(1_800));
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(found.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(found.version()).isEqualTo(1);
        assertThat(found.endToEndId()).isEqualTo(E2E);
        assertThat(found.fee()).isEqualTo(Money.of(100, "BRL"));
        assertThat(found.net()).isEqualTo(Money.of(9_900, "BRL"));
        assertThat(found.lateConfirmation()).isFalse();
        assertThat(found.confirmedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(found.refunded().cents()).isZero();
    }

    @Test
    void resurrection_late_flag_round_trips() {
        var txid = newTxid();
        var p = newPayment(txid)
                .expire(NOW.plusSeconds(4_000))
                .confirm(E2E, BREAKDOWN, NOW.plusSeconds(7_000));
        repository().save(p);

        var found = repository().findByTxid(txid).orElseThrow();
        assertThat(found.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(found.version()).isEqualTo(2);
        assertThat(found.lateConfirmation()).isTrue();
        assertThat(found.confirmedAt()).isEqualTo(NOW.plusSeconds(7_000));
    }

    @Test
    void matching_version_update_returns_true_and_persists_effects() {
        var txid = newTxid();
        repository().save(newPayment(txid));

        var loaded = repository().findByTxid(txid).orElseThrow();
        loaded.confirm(E2E, BREAKDOWN, NOW.plusSeconds(60));

        assertThat(repository().updateIfVersionMatches(loaded, 0)).isTrue();
        assertThat(loaded.version()).isEqualTo(1);

        var reloaded = repository().findByTxid(txid).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.fee()).isEqualTo(Money.of(100, "BRL"));
    }

    @Test
    void stale_version_update_returns_false_and_persists_the_winner_only() {
        var txid = newTxid();
        repository().save(newPayment(txid));

        var loser = repository().findByTxid(txid).orElseThrow();
        loser.confirm(E2E, BREAKDOWN, NOW.plusSeconds(60));

        var winner = repository().findByTxid(txid).orElseThrow();
        winner.expire(NOW.plusSeconds(4_000));
        assertThat(repository().updateIfVersionMatches(winner, 0)).isTrue();

        assertThat(repository().updateIfVersionMatches(loser, 0)).isFalse();

        var reloaded = repository().findByTxid(txid).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.fee()).isNull();
    }

    @Test
    void refreshed_version_allows_a_second_guarded_update() {
        var txid = newTxid();
        repository().save(newPayment(txid));

        var first = repository().findByTxid(txid).orElseThrow();
        first.confirm(E2E, BREAKDOWN, NOW.plusSeconds(60));
        assertThat(repository().updateIfVersionMatches(first, 0)).isTrue();

        var second = repository().findByTxid(txid).orElseThrow();
        second.refund(Money.of(10_000, "BRL"), BREAKDOWN.feeReversalFor(10_000), NOW.plusSeconds(120));
        assertThat(repository().updateIfVersionMatches(second, 1)).isTrue();

        var reloaded = repository().findByTxid(txid).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reloaded.version()).isEqualTo(2);
        assertThat(reloaded.refunded().cents()).isEqualTo(10_000);
    }

    @Test
    void duplicate_txid_save_is_rejected() {
        var txid = newTxid();
        repository().save(newPayment(txid));
        assertThatThrownBy(() -> repository().save(newPayment(txid)))
                .isInstanceOf(DuplicatePaymentTxidException.class);
    }

    @Test
    void guarded_update_on_unknown_txid_returns_false() {
        assertThat(repository().updateIfVersionMatches(newPayment(newTxid()), 0)).isFalse();
    }

    @Test
    void find_by_unknown_txid_is_empty() {
        assertThat(repository().findByTxid(newTxid())).isEmpty();
    }

    private static Payment newPayment(Txid txid) {
        return Payment.create(txid, MERCHANT_ID, Money.of(10_000, "BRL"), "order-1",
                NOW.plusSeconds(1_800), NOW);
    }

    private static Txid newTxid() {
        return new Txid(String.format("TEST%021d", SEQUENCE.incrementAndGet()));
    }
}
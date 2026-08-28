package io.dargent.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void rejects_negative_cents() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(-1, "BRL"));
    }

    @Test
    void rejects_blank_currency() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(100, " "));
    }

    @Test
    void normalizes_currency_case() {
        assertThat(new Money(100, "brl").currency()).isEqualTo("BRL");
    }

    @Test
    void adds_within_the_same_currency() {
        assertThat(Money.of(100, "BRL").plus(Money.of(250, "BRL"))).isEqualTo(Money.of(350, "BRL"));
    }

    @Test
    void refuses_cross_currency_arithmetic() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "BRL").plus(Money.of(100, "USD")));
    }

    @Test
    void subtraction_cannot_go_negative() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "BRL").minus(Money.of(101, "BRL")));
    }

    @Test
    void subtraction_stops_at_zero() {
        assertThat(Money.of(100, "BRL").minus(Money.of(100, "BRL")).isZero()).isTrue();
    }

    @Test
    void compares_only_within_the_same_currency() {
        assertThat(Money.of(100, "BRL")).isGreaterThan(Money.of(99, "BRL"));
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "BRL").compareTo(Money.of(100, "USD")));
    }
}

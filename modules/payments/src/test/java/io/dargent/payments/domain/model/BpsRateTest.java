package io.dargent.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BpsRateTest {

    @Test
    void accepts_bounds_and_midpoint() {
        assertThat(new BpsRate(0).value()).isZero();
        assertThat(new BpsRate(10_000).value()).isEqualTo(10_000);
        assertThat(new BpsRate(100).value()).isEqualTo(100);
    }

    @Test
    void rejects_values_above_ten_thousand_bps() {
        assertThatThrownBy(() -> new BpsRate(10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_negative_values() {
        assertThatThrownBy(() -> new BpsRate(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
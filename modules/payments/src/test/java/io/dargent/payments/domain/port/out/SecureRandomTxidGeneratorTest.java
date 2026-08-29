package io.dargent.payments.domain.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecureRandomTxidGeneratorTest {

    @Test
    void generated_txids_always_pass_txid_validation_over_many_samples() {
        var generator = new SecureRandomTxidGenerator();
        for (int i = 0; i < 100; i++) {
            assertThat(generator.generate().value()).matches("[A-Z0-9]{25}");
        }
    }

    @Test
    void generated_txids_are_distinct_in_a_run() {
        var generator = new SecureRandomTxidGenerator();
        assertThat(java.util.stream.IntStream.range(0, 50)
                        .mapToObj(i -> generator.generate().value())
                        .distinct().count())
                .isEqualTo(50);
    }
}
package io.dargent.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EndToEndIdTest {

    /** Real-world PSP-style sample: {@code E + 31 alphanumeric}, 32 total (Bacen shape). */
    private static final String VALID = "E00416968202009221504E2345678910";

    @Test
    void accepts_a_32_char_end_to_end_id() {
        var endToEndId = new EndToEndId(VALID);
        assertThat(endToEndId.value()).isEqualTo(VALID);
    }

    @Test
    void preserves_case_of_alphanumeric_body() {
        var value = "Eabcdefghijklmnopqrstuvwxyz12345";
        var endToEndId = new EndToEndId(value);
        assertThat(endToEndId.value()).isEqualTo(value);
    }

    @Test
    void rejects_31_chars() {
        assertThatThrownBy(() -> new EndToEndId(VALID.substring(0, 31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_33_chars() {
        assertThatThrownBy(() -> new EndToEndId(VALID + "0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_missing_uppercase_E_prefix() {
        assertThatThrownBy(() -> new EndToEndId("A" + VALID.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndToEndId("e" + VALID.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_and_blank() {
        assertThatThrownBy(() -> new EndToEndId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndToEndId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
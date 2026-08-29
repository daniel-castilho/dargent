package io.dargent.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TxidTest {

    private static final String VALID = "8KD4Z9X2Q7W1M5T3R6Y0A1B2C";

    @Test
    void accepts_a_25_char_alphanumeric_txid() {
        var txid = new Txid(VALID);
        assertThat(txid.value()).isEqualTo(VALID);
    }

    @Test
    void normalizes_lowercase_input_to_uppercase() {
        var txid = new Txid(VALID.toLowerCase());
        assertThat(txid.value()).isEqualTo(VALID);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> new Txid(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_empty() {
        assertThatThrownBy(() -> new Txid(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_blank() {
        assertThatThrownBy(() -> new Txid(" ".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_24_chars() {
        assertThatThrownBy(() -> new Txid(VALID.substring(0, 24)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_26_chars() {
        assertThatThrownBy(() -> new Txid(VALID + "Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_non_alphanumeric_characters() {
        assertThatThrownBy(() -> new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2-"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1 B2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2ç"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
package io.dargent.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * API key hashing contract (E3 spec §3.2, §5.9): SHA-256 hex, constant-time compare, prefix
 * extraction. Pure unit — no Spring context.
 */
class ApiKeyHasherTest {

    @Test
    void hash_is_deterministic() {
        String key = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        String h1 = ApiKeyHasher.hash(key);
        String h2 = ApiKeyHasher.hash(key);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64); // SHA-256 hex = 64 chars
    }

    @Test
    void prefix_extracts_first_11_chars() {
        String key = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        assertThat(ApiKeyHasher.prefix(key)).isEqualTo("psp_test_");
    }

    @Test
    void prefix_throws_on_too_short_key() {
        assertThatThrownBy(() -> ApiKeyHasher.prefix("psp_tes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constant_time_equals_returns_true_for_equal_hashes() {
        String h = "a".repeat(64);
        assertThat(ApiKeyHasher.constantTimeEquals(h, h)).isTrue();
    }

    @Test
    void constant_time_equals_returns_false_for_different_hashes() {
        String h1 = "a".repeat(64);
        String h2 = "b".repeat(64);
        assertThat(ApiKeyHasher.constantTimeEquals(h1, h2)).isFalse();
    }

    @Test
    void constant_time_equals_returns_false_for_null() {
        String h = "a".repeat(64);
        assertThat(ApiKeyHasher.constantTimeEquals(h, null)).isFalse();
        assertThat(ApiKeyHasher.constantTimeEquals(null, h)).isFalse();
    }

    @Test
    void generate_raw_key_produces_valid_format() {
        String key = ApiKeyHasher.generateRawKey();
        assertThat(key).startsWith("psp_test_");
        assertThat(key).hasSizeGreaterThan(11);
        assertThat(key.substring(11)).matches("[A-Za-z0-9]+");
    }
}
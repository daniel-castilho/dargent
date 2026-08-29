package io.dargent.pspsimulator.webhook;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final String SECRET = "dev-only-secret";

    @Test
    void signs_the_spec_54_shared_test_vector_byte_exact() {
        String timestamp = "1787932800";
        String body = "{\"eventId\":\"psp-evt-test-001\",\"type\":\"payment.confirmed\","
                + "\"txid\":\"8KD4Z9X2Q7W1M5T3R6Y0A1B2C\","
                + "\"endToEndId\":\"E9040381234567890123456789012345\","
                + "\"amount\":10000,\"paidAt\":\"2026-08-29T00:00:00Z\"}";

        WebhookSigner signer = new WebhookSigner(SECRET);
        String signature = signer.sign(timestamp, body.getBytes(StandardCharsets.UTF_8));

        assertThat(signature)
                .isEqualTo("549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113");
    }

    @Test
    void signs_an_independent_known_answer_vector() {
        WebhookSigner signer = new WebhookSigner(SECRET);
        assertThat(signer.sign("1", "{}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("e3f75e30c05fa6ab20d1cdd115d4172f6adba335dca3ed37842195aa05305529");
    }

    @Test
    void the_dot_separator_is_literal_and_body_bytes_are_signed_verbatim() {
        WebhookSigner signer = new WebhookSigner(SECRET);
        String body = "{\"a\":1}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        // canonical = UTF-8 bytes of timestamp + "." + rawBody
        assertThat(signer.sign("10", bodyBytes)).isNotEqualTo(signer.sign("1", ("0." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
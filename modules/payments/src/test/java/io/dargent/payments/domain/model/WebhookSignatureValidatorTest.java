package io.dargent.payments.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD for WebhookSignatureValidator (E4 spec §5.2, E3R R5).
 * Pure domain — no Spring, no Jackson. Bytes in, verdict out. Injected Clock.
 * Verifies the shared E2 §5.4 vector byte-exact + independent vector + verdict order
 * + byte-sensitivity (wrong key, flipped byte, 1.0 vs 10, non-canonical order).
 */
class WebhookSignatureValidatorTest {

    private static final String SECRET = "dev-only-secret";
    // Shared E2 §5.4 vector
    private static final String TS_VECTOR1 = "1787932800"; // 2026-06-27T00:00:00Z
    private static final String BODY_VECTOR1 = "{\"eventId\":\"psp-evt-test-001\",\"type\":\"payment.confirmed\",\"txid\":\"8KD4Z9X2Q7W1M5T3R6Y0A1B2C\",\"endToEndId\":\"E9040381234567890123456789012345\",\"amount\":10000,\"paidAt\":\"2026-08-29T00:00:00Z\"}";
    private static final String VECTOR1_SIG = "549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113";
    // Independent vector
    private static final String TS_VECTOR2 = "1";
    private static final String BODY_VECTOR2 = "{}";
    private static final String VECTOR2_SIG = "e3f75e30c05fa6ab20d1cdd115d4172f6adba335dca3ed37842195aa05305529";

    // ------------------------------------------------------------------ shared E2 §5.4 vector (byte-exact)

    @Test
    void shared_vector_byte_exact() {
        // Clock at the vector timestamp (within 300s window)
        Clock clockAtVector = Clock.fixed(Instant.ofEpochSecond(1787932800), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clockAtVector);
        var verdict = validator.verify(TS_VECTOR1, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), VECTOR1_SIG, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
    }

    @Test
    void independent_vector_byte_exact() {
        Clock clockAtVector = Clock.fixed(Instant.ofEpochSecond(1), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clockAtVector);
        var verdict = validator.verify(TS_VECTOR2, BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
    }

    // ------------------------------------------------------------------ verdict order: unparsable timestamp → INVALID

    @Test
    void unparsable_timestamp_is_INVALID() {
        WebhookSignatureValidator validator = new WebhookSignatureValidator(Clock.systemUTC());
        var verdict = validator.verify("not-an-instant", BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), VECTOR1_SIG, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    @Test
    void empty_timestamp_is_INVALID() {
        WebhookSignatureValidator validator = new WebhookSignatureValidator(Clock.systemUTC());
        var verdict = validator.verify("", BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), VECTOR1_SIG, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    // ------------------------------------------------------------------ verdict order: anti-replay ±300s window → EXPIRED

    @Test
    void timestamp_301_seconds_in_future_is_EXPIRED() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        // ts = now + 301s
        String futureTs = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").plusSeconds(301).getEpochSecond());
        var verdict = validator.verify(futureTs, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), VECTOR1_SIG, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.EXPIRED);
    }

    @Test
    void timestamp_301_seconds_in_past_is_EXPIRED() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        // ts = now - 301s
        String pastTs = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").minusSeconds(301).getEpochSecond());
        var verdict = validator.verify(pastTs, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), VECTOR1_SIG, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.EXPIRED);
    }

    @Test
    void timestamp_exactly_300_seconds_in_future_is_VALID() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        String futureTs = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").plusSeconds(300).getEpochSecond());
        String correctSig = validator.computeSignatureForTest(futureTs, BODY_VECTOR1, SECRET);
        var verdict = validator.verify(futureTs, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), correctSig, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
    }

    @Test
    void timestamp_exactly_300_seconds_in_past_is_VALID() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        String pastTs = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").minusSeconds(300).getEpochSecond());
        String correctSig = validator.computeSignatureForTest(pastTs, BODY_VECTOR1, SECRET);
        var verdict = validator.verify(pastTs, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), correctSig, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
    }

    // ------------------------------------------------------------------ HMAC mismatch → INVALID

    @Test
    void wrong_signature_is_INVALID() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        String ts = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").getEpochSecond());
        var verdict = validator.verify(ts, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), "0000000000000000000000000000000000000000000000000000000000000000", SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    @Test
    void wrong_secret_is_INVALID() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        String ts = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").getEpochSecond());
        String correctSig = validator.computeSignatureForTest(ts, BODY_VECTOR1, SECRET);
        var verdict = validator.verify(ts, BODY_VECTOR1.getBytes(StandardCharsets.UTF_8), correctSig, "wrong-secret");
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    // ------------------------------------------------------------------ byte-sensitivity

    @Test
    void flipped_body_byte_is_INVALID() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        String ts = String.valueOf(Instant.parse("2026-08-29T12:00:00Z").getEpochSecond());
        String correctSig = validator.computeSignatureForTest(ts, BODY_VECTOR1, SECRET);
        byte[] body = BODY_VECTOR1.getBytes(StandardCharsets.UTF_8);
        body[0] ^= 0xFF;
        var verdict = validator.verify(ts, body, correctSig, SECRET);
        assertThat(verdict).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    @Test
    void timestamp_1_0_vs_10_is_different_verdict() {
        // Clock at epoch 1 so ts="1" is within 300s window; VECTOR2_SIG is for ts="1", body="{}"
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        var verdict1 = validator.verify("1", BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        var verdict10 = validator.verify("1.0", BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        assertThat(verdict1).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
        assertThat(verdict10).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    @Test
    void timestamp_1_vs_10_is_different() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        var verdict1 = validator.verify("1", BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        var verdict10 = validator.verify("10", BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        assertThat(verdict1).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
        assertThat(verdict10).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }

    @Test
    void non_canonical_timestamp_order_is_different() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1), ZoneOffset.UTC);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(clock);
        var verdict1 = validator.verify("1", BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        var verdict001 = validator.verify("001", BODY_VECTOR2.getBytes(StandardCharsets.UTF_8), VECTOR2_SIG, SECRET);
        assertThat(verdict1).isEqualTo(WebhookSignatureValidator.Verdict.VALID);
        assertThat(verdict001).isEqualTo(WebhookSignatureValidator.Verdict.INVALID);
    }
}
package io.dargent.pspsimulator.webhook;

import java.time.Instant;
import java.nio.charset.StandardCharsets;

import io.dargent.pspsimulator.charge.Charge;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventTest {

    @Test
    void builds_the_exact_event_json_bytes_from_a_paid_charge() throws Exception {
        Charge paid = new Charge("8KD4Z9X2Q7W1M5T3R6Y0A1B2C", 10_000,
                Instant.parse("2026-08-29T01:30:00Z"), "http://api-blue:8080/webhooks/psp", "Order #123");
        paid.pay(Instant.parse("2026-08-29T00:41:12Z"),
                "E9040381234567890123456789012345",
                "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d");

        byte[] json = WebhookEvent.of(paid).toJsonBytes(new ObjectMapper());

        String expected = "{\"eventId\":\"psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d\","
                + "\"type\":\"payment.confirmed\","
                + "\"txid\":\"8KD4Z9X2Q7W1M5T3R6Y0A1B2C\","
                + "\"endToEndId\":\"E9040381234567890123456789012345\","
                + "\"amount\":10000,\"paidAt\":\"2026-08-29T00:41:12Z\"}";
        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo(expected);
    }
}
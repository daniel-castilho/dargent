package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E15 S1 body-cap IT (spec backlog S1.3): oversize → 413 BEFORE HMAC consumption — the rejection
 * decides on the {@code Content-Length} header (zero body bytes read) and the bounded read guards
 * chunked/lying headers; the 413 never persists an attack-audit row (oversize is noise, not an
 * attack signal worth a write); a body at exactly the cap flows through to the HMAC verdict.
 *
 * <p>Test profile tightens the cap to 64 bytes via {@code @DynamicPropertySource}; the same
 * properties feed the production wiring so the test tunes the real path, not a copy of it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, WebhookBodyCapIT.BodyCapTestConfig.class},
        properties = "dargent.psp.webhook-secret=dev-only-secret")
@Testcontainers
class WebhookBodyCapIT {

    private static final long CAP_BYTES = 64;
    private static final Instant START = Instant.parse("2027-01-01T12:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meterRegistry;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void tightCap(DynamicPropertyRegistry registry) {
        registry.add("dargent.webhook.body-cap-bytes", () -> CAP_BYTES);
        // Rate limit huge: this IT isolates the body cap.
        registry.add("dargent.webhook.rate-limit.capacity", () -> 10_000);
        registry.add("dargent.webhook.rate-limit.refill-per-second", () -> 100.0);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                        + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade")
                .update();
    }

    @Test
    void oversize_body_is_413_before_hmac_consumption() throws Exception {
        double rejectionsBefore = rejectionCount("body_too_large");
        String big = "x".repeat(1000);
        var resp = post(big);
        assertThat(resp.statusCode()).isEqualTo(413);
        assertThat(resp.body()).contains("payload_too_large");

        // Zero side effects: the cap verdict precedes the HMAC flow — nothing persisted.
        Integer rows = jdbc.sql("select count(*) from payments.webhook_events")
                .query(Integer.class)
                .single();
        assertThat(rows).isZero();
        // The frozen rejection counter feeds the S2 alert rule.
        assertThat(rejectionCount("body_too_large")).isEqualTo(rejectionsBefore + 1);
    }

    @Test
    void body_at_exactly_cap_flows_through_to_the_hmac_verdict() throws Exception {
        // Exactly 64 bytes: under-or-at cap → reaches the app → fails closed on HMAC (401) with
        // the attack-audit row — proving the cap does not eat valid boundary traffic.
        String body = "{\"eventId\":\"e\",\"type\":\"x\"}"; // 25 bytes
        String padded = body + " ".repeat((int) CAP_BYTES - body.length());
        assertThat(padded.getBytes(StandardCharsets.UTF_8)).hasSize((int) CAP_BYTES);

        var resp = post(padded);
        assertThat(resp.statusCode()).isEqualTo(401);
        Integer rows = jdbc.sql("select count(*) from payments.webhook_events")
                .query(Integer.class)
                .single();
        assertThat(rows).isOne();
    }

    @Test
    void chunked_body_without_content_length_is_capped_by_the_bounded_read() throws Exception {
        double rejectionsBefore = rejectionCount("body_too_large");
        // No Content-Length (chunked semantics): the bounded read still trips the cap at cap+1.
        String big = "y".repeat(500);
        var resp = postChunked(big);
        assertThat(resp.statusCode()).isEqualTo(413);
        assertThat(rejectionCount("body_too_large")).isEqualTo(rejectionsBefore + 1);
    }

    // ------------------------------------------------------------------------------ helpers

    private double rejectionCount(String reason) {
        return meterRegistry
                .counter("dargent.webhook.rejections", "reason", reason)
                .count();
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/webhooks/psp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** No Content-Length: the JDK client sends a fixed-length body without the header only when
     * the publisher is unknown-length — emulate with a streaming publisher. */
    private HttpResponse<String> postChunked(String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/webhooks/psp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ================================================================================ config

    @Configuration
    static class BodyCapTestConfig {

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(
                            "classpath:db/migration/payments",
                            "classpath:db/migration/ledger",
                            "classpath:db/migration/notifications")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(START, ZoneOffset.UTC);
        }
    }
}

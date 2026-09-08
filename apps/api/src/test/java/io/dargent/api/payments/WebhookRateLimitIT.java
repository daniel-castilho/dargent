package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.web.WebhookRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * E15 S1 rate-limit IT (spec backlog S1.3): burst beyond capacity → 429; recovery window after
 * refill → admitted again; and the carved control order — an over-limit request produces ZERO
 * money-path side effects (no webhook_events row, no audit row) while a same-body valid request
 * under the limit flows byte-identically to the pre-S1 intake.
 *
 * <p>Determinism: a mutable {@link Clock} drives the bucket refill — the recovery window is
 * advanced, never slept (AGENTS §5.3). Test profile tightens capacity to 3/refill 0.5/s via
 * {@code @DynamicPropertySource}; the same properties feed the production wiring so the test
 * tunes the real path, not a copy of it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, WebhookRateLimitIT.RateLimitTestConfig.class},
        properties = "dargent.psp.webhook-secret=dev-only-secret")
@Testcontainers
class WebhookRateLimitIT {

    /** Tight for the test; ITs tune limits explicitly (spec §4). */
    private static final long CAPACITY = 3;

    private static final double REFILL_PER_SECOND = 0.5;
    private static final Instant START = Instant.parse("2027-01-01T12:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WebhookRateLimiter rateLimiter;

    @Autowired
    MutableClock clock;

    @Autowired
    MeterRegistry meterRegistry;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void tightLimits(DynamicPropertyRegistry registry) {
        registry.add("dargent.webhook.rate-limit.capacity", () -> CAPACITY);
        registry.add("dargent.webhook.rate-limit.refill-per-second", () -> REFILL_PER_SECOND);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        rateLimiter.reset();
        clock.reset();
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                        + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade")
                .update();
    }

    @Test
    void burst_beyond_capacity_is_429_and_recovery_window_admits_again() throws Exception {
        double rejectionsBefore = rejectionCount("rate_limited");
        // Burst: capacity (3) requests admitted…
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(postUnsigned("{}").statusCode()).isEqualTo(401); // reached the app: HMAC verdict
        }
        // …the 4th is over-limit → 429 (decided before the body was even buffered).
        assertThat(postUnsigned("{}").statusCode()).isEqualTo(429);

        // Recovery: 2 s of refill at 0.5/s → exactly one token → one admission, then 429 again.
        clock.advance(Duration.ofSeconds(2));
        assertThat(postUnsigned("{}").statusCode()).isEqualTo(401);
        assertThat(postUnsigned("{}").statusCode()).isEqualTo(429);

        // The frozen rejection counter feeds the S2 alert rule: both 429s counted, none leaked.
        assertThat(rejectionCount("rate_limited")).isEqualTo(rejectionsBefore + 2);
    }

    @Test
    void over_limit_request_has_zero_money_path_side_effects() throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            postUnsigned("{}");
        }
        long rowsBefore = webhookEventCount();
        double rejectionsBefore = rejectionCount("rate_limited");

        var rejected = postUnsigned("{}");
        assertThat(rejected.statusCode()).isEqualTo(429);

        // Carved contract: a 429 never persists an attack-audit row (the pre-S1 flow persists even
        // invalid signatures). A rate-limited flood must not amplify into a write flood.
        assertThat(webhookEventCount()).isEqualTo(rowsBefore);
        assertThat(rejectionCount("rate_limited")).isEqualTo(rejectionsBefore + 1);
    }

    @Test
    void valid_traffic_under_limit_flows_unchanged() throws Exception {
        // Under the limit the request reaches the app and fails closed on HMAC exactly as before
        // S1 — the filter is invisible to valid traffic (401 with an attack-audit row).
        var resp = postUnsigned("{\"eventId\":\"e1\",\"type\":\"payment.confirmed\"}");
        assertThat(resp.statusCode()).isEqualTo(401);
        Integer rows = jdbc.sql("select count(*) from payments.webhook_events")
                .query(Integer.class)
                .single();
        assertThat(rows).isOne();
    }

    // ------------------------------------------------------------------------------ helpers

    private long webhookEventCount() {
        return jdbc.sql("select count(*) from payments.webhook_events")
                .query(Long.class)
                .single();
    }

    private double rejectionCount(String reason) {
        return meterRegistry
                .counter("dargent.webhook.rejections", "reason", reason)
                .count();
    }

    private HttpResponse<String> postUnsigned(String body) throws Exception {
        return post(body, null, null);
    }

    private HttpResponse<String> post(String body, String ts, String signature) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/webhooks/psp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ts != null) {
            builder.header("X-PSP-Timestamp", ts);
        }
        if (signature != null) {
            builder.header("X-PSP-Signature", signature);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ================================================================================ config

    /** A clock whose instant can be advanced by exact refill windows (no sleeps). */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        void reset() {
            this.now = START;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Configuration
    static class RateLimitTestConfig {

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
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }
}

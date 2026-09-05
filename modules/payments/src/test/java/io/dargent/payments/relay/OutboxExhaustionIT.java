package io.dargent.payments.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.dargent.payments.adapter.out.messaging.SnsEventPublisher;
import io.dargent.payments.adapter.out.persistence.JdbcOutboxEventStore;
import io.dargent.payments.application.OutboxDeliveryUseCase;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * E9 §6.1 OutboxExhaustionIT: a forced publisher failure ×3 (a `maxAttempts` of 3) drives a PENDING
 * outbox row to EXHAUSTED, and an EXHAUSTED row is never re-claimed by a later relay cycle. The
 * ladder rungs (30 s, 2 m) are asserted via the injected mutable Clock — never sleeps. Complements
 * OutboxRelayIT (E6 §7): the happy/retry/race/purge paths live there; this pins the ceiling.
 */
@Testcontainers
class OutboxExhaustionIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC = "dargent-payments-events-exh.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    // Start just at epoch-ish; the mutable clock is advanced by exact ladder rungs between cycles.
    private static final Instant START = Instant.parse("2026-08-30T12:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static SnsClient sns;
    private static String topicArn;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(4);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/payments")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();

        sns = snsClient();
        topicArn = sns.createTopic(CreateTopicRequest.builder()
                .name(TOPIC)
                .attributes(Map.of("FifoTopic", "true"))
                .build())
                .topicArn();

        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void tearDown() {
        sns.close();
        dataSource.close();
    }

    @BeforeEach
    void clean() {
        jdbc.sql("truncate payments.outbox").update();
        CLOCK.reset();
    }

    // ------------------------------------------------------------- the contract

    /**
     * E9 §2: a row whose publish keeps failing is pushed up the 30s/2m ladder and, on the third
     * failure, lands EXHAUSTED; the fourth cycle (even after the 5m ceiling) claims nothing.
     */
    @Test
    void publish_failure_x3_marks_EXHAUSTED_and_never_reclaims() {
        UUID rowId = UUID.randomUUID();
        String txid = "TXID000000000000000000001";
        String eventId = UUID.randomUUID().toString();
        seed(rowId, txid, "payment.created", envelope(eventId, txid), "req-exh-1");

        // A publisher aimed at a topic that does not exist fails fast — deterministic forced failure.
        String brokenArn = topicArn.replace(TOPIC, TOPIC + "-missing");
        OutboxDeliveryUseCase broken = new OutboxDeliveryUseCase(
                new JdbcOutboxEventStore(jdbc), publisher(brokenArn), MAPPER, CLOCK,
                new OutboxDeliveryUseCase.Policy(32, 2, 1000, 3,
                        Duration.ofSeconds(30), Duration.ofMinutes(5), 7),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new io.dargent.payments.application.PaymentsMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        // ---- attempt 1 ---- ladder rung 1 (30 s), still PENDING
        assertThat(broken.runOnce(32)).isZero();
        assertRow(rowId, "PENDING", 1, CLOCK.instant().plus(Duration.ofSeconds(30)));

        // ---- attempt 2 ---- ladder rung 2 (2 m), still PENDING
        CLOCK.advance(Duration.ofSeconds(30));
        assertThat(broken.runOnce(32)).isZero();
        assertRow(rowId, "PENDING", 2, CLOCK.instant().plus(Duration.ofMinutes(2)));

        // ---- attempt 3 ---- EXHAUSTED, no further backoff scheduled
        CLOCK.advance(Duration.ofMinutes(2));
        assertThat(broken.runOnce(32)).isZero();
        assertRow(rowId, "EXHAUSTED", 3, null);

        // ---- never re-claimed (unscheduled): even past the 5m ceiling, the relay claims nothing
        CLOCK.advance(Duration.ofMinutes(5));
        assertThat(broken.runOnce(32)).isZero();
        assertRow(rowId, "EXHAUSTED", 3, null);
        Long pending = jdbc.sql("select count(*) from payments.outbox where status='PENDING'")
                .query(Long.class).single();
        assertThat(pending).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void assertRow(UUID id, String status, int attempts, Instant nextAttemptAt) {
        if (nextAttemptAt == null) {
            // EXHAUSTED: only status + attempt_count are contract (§2); next_attempt_at is stale
            // and irrelevant — EXHAUSTED rows are never re-claimed regardless of its value.
            Object[] row = jdbc.sql(
                    "select status, attempt_count from payments.outbox where id = :id")
                    .param("id", id)
                    .query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)})
                    .single();
            assertThat(row[0]).isEqualTo(status);
            assertThat(row[1]).isEqualTo(attempts);
        } else {
            Object[] row = jdbc.sql(
                    "select status, attempt_count, next_attempt_at from payments.outbox where id = :id")
                    .param("id", id)
                    .query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getTimestamp(3)})
                    .single();
            assertThat(row[0]).isEqualTo(status);
            assertThat(row[1]).isEqualTo(attempts);
            Instant actual = ((java.sql.Timestamp) row[2]).toInstant();
            assertThat(actual).isEqualTo(nextAttemptAt);
        }
    }

    private static void seed(UUID id, String txid, String type, String payloadJson, String requestId) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'PENDING', 0, '2026-08-29T09:59:00Z')
                """)
                .param("id", id)
                .param("agg", txid)
                .param("type", type)
                .param("payload", payloadJson)
                .param("req", requestId)
                .update();
    }

    private static SnsClient snsClient() {
        return SnsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(
                        localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString()))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .build();
    }

    private static SnsEventPublisher publisher(String arn) {
        return new SnsEventPublisher(arn, 2000, REGION,
                localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString(), "test", "test");
    }

    /** Full E3 §5.6 envelope payload with eventId. */
    private static String envelope(String eventId, String txid) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("txid", txid);
            payload.put("merchantId", MERCHANT.toString());
            payload.put("amount", 10_000);
            payload.put("description", "exhaustion-it");
            payload.put("expiresAt", "2026-08-30T12:05:00Z");
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("eventId", eventId);
            envelope.put("type", "payment.created");
            envelope.put("version", 1);
            envelope.put("aggregateId", txid);
            envelope.put("merchantId", MERCHANT.toString());
            envelope.put("requestId", "req-exh-1");
            envelope.put("occurredAt", "2026-08-29T10:00:00Z");
            envelope.put("payload", payload);
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A clock whose instant can be advanced by exact ladder rungs (no sleeps). */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void reset() { this.now = START; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
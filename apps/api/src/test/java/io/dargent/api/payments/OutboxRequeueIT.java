package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.payments.application.OutboxDeliveryUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;

/**
 * E9 §6.2 OutboxRequeueIT (Q11 adjudication) — scenario 19 end-to-end: an EXHAUSTED outbox row is
 * requeued over the real HTTP admin endpoint (audited with the real admin API-key principal), then
 * the relay delivers it → SENT. Also pins the negatives: double-requeue (already PENDING) → 409
 * {@code not_exhaustible}, requeue-of-PENDING → 409, unknown id → 404, and the auth ladder (no key →
 * 401, revoked key → 401 even when it is the designated admin key — validation first). The 403 leg
 * lives in {@link RotationWindow}: under the committed one-active-key-per-prefix schema, key rotation
 * is necessarily revoke-then-provision, so the stale-config window is a guaranteed operational state —
 * an active key presented while {@code DARGENT_OUTBOX_ADMIN_KEY} still points at the revoked
 * predecessor → 403 fail-closed. Clock is injected and shared by requeue and relay; zero sleeps.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, OutboxRequeueIT.RequeueTestConfig.class},
    properties = {
        "dargent.relay.enabled=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class OutboxRequeueIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_KEY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_KEY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);

    // The admin key value is a real ACTIVE API key (raw present in api_keys) whose hash the controller
    // compares against DARGENT_OUTBOX_ADMIN_KEY — giving a REAL audit actor, never the sentinel.
    private static final String ADMIN_RAW_KEY = ApiKeyHasher.generateRawKey();
    // A REVOKED predecessor sharing the same active prefix — the rotation-window actor (Q11). The
    // filter rejects it before any admin-gate comparison: validation always precedes the env match.
    private static final String PREV_RAW_KEY = ApiKeyHasher.generateRawKey();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OutboxDeliveryUseCase relay;

    @Autowired
    OutboxDeliveryUseCase.Policy relayPolicy;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @org.springframework.test.context.DynamicPropertySource
    static void awsEnvironment(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ensureTopology();
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SNS).toString());
        registry.add("AWS_REGION", () -> "us-east-1");
        registry.add("AWS_ACCESS_KEY_ID", () -> "test");
        registry.add("AWS_SECRET_ACCESS_KEY", () -> "test");
        registry.add("DARGENT_EVENTS_TOPIC_ARN", () -> topicArn);
        registry.add("DARGENT_EVENTS_PUBLISH_TIMEOUT_MS", () -> "2000");
        registry.add("DARGENT_RELAY_BATCH", () -> "32");
        registry.add("DARGENT_RELAY_WORKERS", () -> "2");
        registry.add("DARGENT_RELAY_POLL_MS", () -> "600000");
        registry.add("DARGENT_OUTBOX_RETENTION_DAYS", () -> "7");
        registry.add("DARGENT_RELAY_MAX_ATTEMPTS", () -> "3");
        registry.add("DARGENT_OUTBOX_ADMIN_KEY", () -> ADMIN_RAW_KEY);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.outbox, payments.audit_log, payments.api_keys "
                + "restart identity cascade").update();
        // Single active key (the one-active-per-prefix schema): ADMIN_RAW_KEY is the admin key.
        insertKey(ADMIN_KEY_ID, ADMIN_RAW_KEY, null);
    }

    // ------------------------------------------------------------- scenario 19 e2e

    /**
     * Scenario 19 end-to-end: EXHAUSTED row → audited requeue (real admin actor) → 200 PENDING/0 →
     * relay runOnce → SENT with attempt_count 1. One audit row before the relay, none added by the
     * publish.
     */
    @Test
    void requeue_exhausted_row_then_relay_publishes_to_SENT() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedExhausted(rowId);
        long auditsBefore = auditCount();

        var resp = requeue(rowId, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"status\":\"PENDING\"", "\"attemptCount\":0");
        assertThat(status(rowId)).isEqualTo("PENDING");
        assertThat(attemptCount(rowId)).isEqualTo(0);

        // audit: outbox_requeued with the REAL admin principal (not the sentinel)
        assertThat(auditCount()).isEqualTo(auditsBefore + 1);
        String actor = jdbc.sql(
                "select actor_key_id::text from payments.audit_log where command_name='outbox_requeued'")
                .query(String.class).single();
        assertThat(actor).isEqualTo(ADMIN_KEY_ID.toString());

        // relay picks it up on the next poll -> SENT (attempt_count resets to 1 on publish)
        int delivered = relay.runOnce(relayPolicy.batchSize());
        assertThat(delivered).isEqualTo(1);
        assertThat(status(rowId)).isEqualTo("SENT");
        assertThat(attemptCount(rowId)).isEqualTo(1);
    }

    /** A second requeue on a now-PENDING row: same single effect — the row is not re-armed. */
    @Test
    void double_requeue_is_a_single_effect_not_a_reset() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedExhausted(rowId);

        assertThat(requeue(rowId, ADMIN_RAW_KEY).statusCode()).isEqualTo(200);
        assertThat(status(rowId)).isEqualTo("PENDING");
        assertThat(attemptCount(rowId)).isEqualTo(0);

        // second requeue: now PENDING -> 409 not_exhaustible, row unchanged
        var second = requeue(rowId, ADMIN_RAW_KEY);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("not_exhaustible");
        assertThat(status(rowId)).isEqualTo("PENDING");
        assertThat(attemptCount(rowId)).isEqualTo(0);
    }

    /** Requeue of an already-PENDING row → 409 not_exhaustible. */
    @Test
    void requeue_of_pending_row_is_rejected_409() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedPending(rowId);

        var resp = requeue(rowId, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("not_exhaustible");
        assertThat(status(rowId)).isEqualTo("PENDING");
    }

    /** Unknown id → 404. */
    @Test
    void requeue_unknown_id_is_404() throws Exception {
        var resp = requeue(UUID.randomUUID(), ADMIN_RAW_KEY);
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    /** No Authorization header → 401 (filter). */
    @Test
    void requeue_without_key_is_401() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedExhausted(rowId);
        var resp = requeue(rowId, null);
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    /**
     * A revoked key is 401 even when it IS the designated admin key: the env match never bypasses
     * validation (Q11 — src validation first). The revoked predecessor {@code PREV_RAW_KEY} is the
     * very key DARGENT_OUTBOX_ADMIN_KEY points at, yet the filter rejects it before the admin gate.
     */
    @Test
    void revoked_key_is_401_even_when_it_is_the_admin_key() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedExhausted(rowId);
        insertKey(OTHER_KEY_ID, PREV_RAW_KEY, "2027-01-01T11:00:00Z");
        assertThat(auditCount()).isZero();

        assertThat(requeue(rowId, PREV_RAW_KEY).statusCode()).isEqualTo(401);
        // untouched: still EXHAUSTED, nothing re-armed, no audit
        assertThat(status(rowId)).isEqualTo("EXHAUSTED");
        assertThat(auditCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> requeue(UUID id, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/outbox/" + id + "/requeue"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-" + id)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void seedExhausted(UUID id) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'EXHAUSTED', 3, '2027-01-01T11:00:00Z')
                """)
                .param("id", id)
                .param("agg", txid(id))
                .param("type", "payment.created")
                .param("payload", envelope(id))
                .param("req", "req-exh-" + id)
                .update();
    }

    private void seedPending(UUID id) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'PENDING', 1, '2099-01-01T00:00:00Z')
                """)
                .param("id", id)
                .param("agg", txid(id))
                .param("type", "payment.created")
                .param("payload", envelope(id))
                .param("req", "req-pend-" + id)
                .update();
    }

    private void insertKey(UUID id, String rawKey, String revokedAt) {
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), :revoked)")
                .param("id", id)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .param("revoked", revokedAt == null ? null : java.sql.Timestamp.from(
                        Instant.parse(revokedAt)))
                .update();
    }

    private String status(UUID id) {
        return jdbc.sql("select status from payments.outbox where id = :id")
                .param("id", id).query(String.class).single();
    }

    private int attemptCount(UUID id) {
        return jdbc.sql("select attempt_count from payments.outbox where id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private long auditCount() {
        return jdbc.sql("select count(*) from payments.audit_log").query(Long.class).single();
    }

    private static String txid(UUID id) {
        String hex = id.toString().replace("-", "");
        return "TXID" + hex.substring(0, 21).toUpperCase();
    }

    private static String envelope(UUID id) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("txid", txid(id));
            payload.put("merchantId", MERCHANT.toString());
            payload.put("amount", 10_000);
            payload.put("description", "requeue-it");
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("type", "payment.created");
            envelope.put("version", 1);
            envelope.put("aggregateId", txid(id));
            envelope.put("merchantId", MERCHANT.toString());
            envelope.put("requestId", "req-" + id);
            envelope.put("occurredAt", "2027-01-01T10:00:00Z");
            envelope.put("payload", payload);
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @TestConfiguration
    static class RequeueTestConfig {
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
            return FIXED_CLOCK;
        }
    }

    // ------------------------------ topology (mirrors OutboxDeliveryE2EIT)

    private static String topicArn;

    private static synchronized void ensureTopology() {
        if (topicArn != null) {
            return;
        }
        var sqs = software.amazon.awssdk.services.sqs.SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(software.amazon.awssdk.regions.Region.of("us-east-1"))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                .build();
        var sns = software.amazon.awssdk.services.sns.SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
                .region(software.amazon.awssdk.regions.Region.of("us-east-1"))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                .build();
        String dlqArn = createQueue(sqs, "dargent-requeue-dlq.fifo", null);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
        String notifyUrl = createQueue(sqs, "dargent-requeue-notify.fifo", redrive);
        String notifyArn = queueArn(sqs, notifyUrl);
        topicArn = sns.createTopic(r -> r.name("dargent-requeue-events.fifo")
                .attributes(Map.of("FifoTopic", "true"))).topicArn();
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(notifyArn));
    }

    private static String createQueue(software.amazon.awssdk.services.sqs.SqsClient client, String name, String redrive) {
        Map<software.amazon.awssdk.services.sqs.model.QueueAttributeName, String> attrs =
                new java.util.LinkedHashMap<>();
        attrs.put(software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true");
        if (redrive != null) {
            attrs.put(software.amazon.awssdk.services.sqs.model.QueueAttributeName.REDRIVE_POLICY, redrive);
        }
        return client.createQueue(r -> r.queueName(name).attributes(attrs)).queueUrl();
    }

    private static String queueArn(software.amazon.awssdk.services.sqs.SqsClient client, String url) {
        return client.getQueueAttributes(r -> r.queueUrl(url)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN))
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);
    }
}
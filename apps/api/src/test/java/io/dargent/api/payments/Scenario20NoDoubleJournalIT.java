package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.adapter.out.messaging.SqsEventConsumer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * E9 §6.4 Scenario 20 no-double-journaling proof: a republished {@code payment.confirmed} event
 * consumed twice-equivalently → journal count unchanged, balances unchanged.
 *
 * Flow:
 * 1. Seed a SENT payment.confirmed outbox row (simulating relay already published it).
 * 2. Run the relay → ledger consumes → journal entry + postings + balances created.
 * 3. Republish that same row via /v1/outbox/republish (minted PENDING with eventId-r1).
 * 4. Run the relay again → new PENDING row claimed → ledger consumes republished event.
 * 5. Assert: journal count still 1, balances unchanged (no double journaling).
 *
 * Clock injected, zero sleeps. Extends the RefundFlow harness pattern.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, Scenario20NoDoubleJournalIT.Scenario20TestConfig.class},
    properties = {
        "dargent.relay.enabled=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class Scenario20NoDoubleJournalIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_KEY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-03T12:00:00Z"), ZoneOffset.UTC);

    private static final String ADMIN_RAW_KEY = ApiKeyHasher.generateRawKey();

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
    SqsEventConsumer ledgerConsumer;

    @Autowired
    io.dargent.payments.application.OutboxDeliveryUseCase relay;

    @Autowired
    io.dargent.payments.application.OutboxDeliveryUseCase.Policy relayPolicy;

    @Autowired
    io.dargent.ledger.application.EventIngestionUseCase ledgerIngestion;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @org.springframework.test.context.DynamicPropertySource
    static void env(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
        jdbc.sql("truncate payments.outbox, payments.audit_log, payments.api_keys, "
                + "ledger.events, ledger.journal_entries, ledger.postings, ledger.balances, ledger.audit_log "
                + "restart identity cascade").update();
        insertKey(ADMIN_KEY_ID, ADMIN_RAW_KEY, null);
    }

@Disabled("HOLD: owner re-baselining S4 scenario-20 harness per audit — deterministic EventIngestionUseCase direct invocation replacing SNS/SQS path")
    @Test
    void republished_payment_confirmed_consumed_twice_equivalently_no_double_journal() throws Exception {
        // 1) Seed one PENDING payment.confirmed outbox row (relay will publish it)
        UUID outboxId = UUID.randomUUID();
        String txid = txid(outboxId);
        seedPendingPaymentConfirmed(outboxId, txid);

        // 2) Run relay + ledger consumer once → original event published, ledger consumes → journal created
        int relayRuns = relayAndConsumeOnce();
        assertThat(relayRuns).isGreaterThanOrEqualTo(1);

        long journalCountAfterOriginal = countJournalEntries(txid);
        long balanceBefore = getAvailableBalance();
        assertThat(journalCountAfterOriginal).isEqualTo(1);
        assertThat(balanceBefore).isGreaterThan(0);

        // 3) Republish the SENT row → mints new PENDING with eventId-r1
        String from = "2027-01-03T09:00:00Z";
        String to = "2027-01-03T12:00:00Z";
        var resp = republish(ADMIN_RAW_KEY, from, to, null);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"matched\":1");
        assertThat(resp.body()).contains("\"republished\":1");

        // 4) Run relay + ledger consumer again → republished event consumed
        relayAndConsumeOnce();

        // 5) Assert no double journaling: journal count unchanged, balances unchanged
        long journalCountAfterRepublish = countJournalEntries(txid);
        long balanceAfter = getAvailableBalance();

        assertThat(journalCountAfterRepublish).as("Journal count must not increase for republished event")
                .isEqualTo(journalCountAfterOriginal);
        assertThat(balanceAfter).as("Balances must not change for republished event")
                .isEqualTo(balanceBefore);
    }

    // ------------------------------------------------------------- helpers

    private void seedPendingPaymentConfirmed(UUID outboxId, String txid) {
        // Payload with payment.confirmed type and amount
        String payload = envelope(outboxId, "payment.confirmed", txid, 10_000, 500);
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'PENDING', 0, :next)
                """)
                .param("id", outboxId)
                .param("agg", txid)
                .param("type", "payment.confirmed")
                .param("payload", payload)
                .param("req", "req-" + outboxId)
                .param("next", Timestamp.from(Instant.parse("2027-01-03T10:00:00Z")))
                .update();
    }

    private int relayAndConsumeOnce() {
        // 1) Relay claims PENDING outbox rows and publishes to SNS
        int relayCount = relay.runOnce(relayPolicy.batchSize());
        // 2) Ledger consumer polls SQS and processes messages
        int consumed = ledgerConsumer.runOnce();
        // Total messages processed (relay publishes, consumer consumes)
        return relayCount + consumed;
    }

    private long countJournalEntries(String txid) {
        return jdbc.sql("select count(*) from ledger.journal_entries where txid = :txid")
                .param("txid", txid).query(Long.class).single();
    }

    private long getAvailableBalance() {
        return jdbc.sql("select balance_cents from ledger.balances where account = :acc")
                .param("acc", "merchant:" + MERCHANT + ":available")
                .query(Long.class).optional().orElse(0L);
    }

    private HttpResponse<String> republish(String bearer, String from, String to, List<String> types) throws Exception {
        Map<String, Object> body = Map.of("from", from, "to", to);
        if (types != null && !types.isEmpty()) {
            body = new java.util.LinkedHashMap<>(body);
            body.put("types", types);
        }
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(body);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/outbox/republish"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-scn20-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void insertKey(UUID id, String rawKey, String revokedAt) {
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), :revoked)")
                .param("id", id)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .param("revoked", revokedAt == null ? null : Timestamp.from(Instant.parse(revokedAt)))
                .update();
    }

    private static String txid(UUID id) {
        String hex = id.toString().replace("-", "");
        return "TXID" + hex.substring(0, 21).toUpperCase();
    }

    private static String envelope(UUID id, String type, String txid, long amount, long fee) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("txid", txid);
            payload.put("merchantId", MERCHANT.toString());
            payload.put("amount", amount);
            payload.put("fee", fee);
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("type", type);
            envelope.put("version", 1);
            envelope.put("aggregateId", txid);
            envelope.put("merchantId", MERCHANT.toString());
            envelope.put("requestId", "req-" + id);
            envelope.put("occurredAt", "2027-01-03T10:00:00Z");
            envelope.put("payload", payload);
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @TestConfiguration
    static class Scenario20TestConfig {
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
        software.amazon.awssdk.services.sqs.SqsClient ledgerTestSqsClient() {
            return sqsClient;
        }

        @Bean
        SqsEventConsumer ledgerConsumer(
                io.dargent.ledger.application.EventIngestionUseCase ingestion,
                software.amazon.awssdk.services.sqs.SqsClient ledgerTestSqsClient) {
            return new SqsEventConsumer(ledgerTestSqsClient, ledgerUrl, 10, 600000, ingestion);
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    // ------------------------------ topology (mirrors OutboxDeliveryE2EIT)

    private static String topicArn;
    private static String ledgerUrl;
    private static software.amazon.awssdk.services.sqs.SqsClient sqsClient;

    private static synchronized void ensureTopology() {
        if (topicArn != null) {
            return;
        }
        sqsClient = software.amazon.awssdk.services.sqs.SqsClient.builder()
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
        String dlqArn = createQueue(sqsClient, "dargent-scn20-dlq.fifo", null);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
        String notifyUrl = createQueue(sqsClient, "dargent-scn20-notify.fifo", redrive);
        String notifyArn = queueArn(sqsClient, notifyUrl);

        // Ledger queue (separate DLQ)
        String ledgerDlqArn = createQueue(sqsClient, "dargent-scn20-ledger-dlq.fifo", null);
        String ledgerRedrive = "{\"deadLetterTargetArn\":\"" + ledgerDlqArn + "\",\"maxReceiveCount\":\"5\"}";
        ledgerUrl = createQueue(sqsClient, "dargent-scn20-ledger.fifo", ledgerRedrive);
        String ledgerArn = queueArn(sqsClient, ledgerUrl);

        topicArn = sns.createTopic(r -> r.name("dargent-scn20-events.fifo")
                .attributes(Map.of("FifoTopic", "true"))).topicArn();
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(notifyArn));
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(ledgerArn)
                .attributes(Map.of("RawMessageDelivery", "true")));
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
package io.dargent.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.notifications.adapter.out.messaging.SqsNotificationConsumer;
import io.dargent.notifications.application.NotificationIngestionUseCase;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter;
import io.dargent.payments.application.OutboxDeliveryUseCase;
import io.dargent.payments.domain.port.out.PspPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * S5 — E10 Integration Tests (E10 spec §8).
 * IT1: NotificationLoopIT — full loop: publish event → SQS → consumer runOnce → row exists, ack;
 *      same event again → still one row (dedupe), zero writes.
 * IT2: NotificationPoisonDlqIT — malformed body → not acked → maxReceive attempts → lands in notify DLQ.
 * Both green in CI; no sleeps; names locked by spec §8.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, NotificationLoopIT.NotificationsTestConfig.class},
    properties = {
        "dargent.relay.enabled=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class NotificationLoopIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_NAME = "dargent-payments-events-notifications-ml.fifo";
    private static final String NOTIFS_QUEUE = "dargent-payments-notifications-ml.fifo";
    private static final String NOTIFS_DLQ = "dargent-payments-notifications-dlq-ml.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final long FIXED_NOW_SECS = FIXED_CLOCK.instant().getEpochSecond();
    private static final String PAID_AT = FIXED_CLOCK.instant().plusSeconds(120).toString();
    private static final String SECRET = "dev-only-secret";
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    private static SnsClient sns;
    private static SqsClient sqs;
    private static String notifsUrl;
    private static String topicArn;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OutboxDeliveryUseCase relay;

    @Autowired
    OutboxDeliveryUseCase.Policy relayPolicy;

    @Autowired
    SqsNotificationConsumer notifsConsumer;

    @Autowired
    NotificationIngestionUseCase ingestion;

    @Autowired
    PspStub psp;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @org.springframework.test.context.DynamicPropertySource
    static void awsEnvironment(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ensureTopology();
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SNS).toString());
        registry.add("AWS_REGION", () -> REGION);
        registry.add("AWS_ACCESS_KEY_ID", () -> "test");
        registry.add("AWS_SECRET_ACCESS_KEY", () -> "test");
        registry.add("DARGENT_EVENTS_TOPIC_ARN", () -> topicArn);
        registry.add("DARGENT_EVENTS_PUBLISH_TIMEOUT_MS", () -> "2000");
        registry.add("DARGENT_RELAY_BATCH", () -> "32");
        registry.add("DARGENT_RELAY_WORKERS", () -> "2");
        registry.add("DARGENT_RELAY_POLL_MS", () -> "600000");
        registry.add("DARGENT_OUTBOX_RETENTION_DAYS", () -> "7");
        registry.add("DARGENT_NOTIFS_QUEUE_URL", () -> notifsUrl);
        registry.add("DARGENT_NOTIFS_BATCH", () -> "10");
        registry.add("DARGENT_NOTIFS_POLL_MS", () -> "1000");
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate notifications.notification, ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.settlements, ledger.audit_log, "
                + "payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'notifs-it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        psp.reset();
    }

    /** IT1 — full loop: HTTP create → webhook → relay → notifications consumer → row exists, ack. */
    @Test
    void confirmed_payment_flows_to_notifications_with_row_and_ack() throws Exception {
        String txid = createPayment("notifs-loop-01");
        String endToEndId = "E9040381234567890123456789012345";

        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), confirmedBody(txid, endToEndId, 10000));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");

        // Drive the payments relay deterministically, then the notifications consumer runOnce
        int relayed = relay.runOnce(relayPolicy.batchSize());
        assertThat(relayed).isGreaterThan(0);
        for (int i = 0; i < 8 && notificationCount() == 0; i++) {
            notifsConsumer.runOnce();
        }
        assertThat(notificationCount()).as("confirmed should create a notification row").isEqualTo(1);

        // Verify event row
        Map<String, Object> row = jdbc.sql(
                "select event_id, type, txid, merchant_id, payload, occurred_at, created_at "
                        + "from notifications.notification where txid = :t")
                .param("t", txid)
                .query((rs, i) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("event_id", rs.getObject("event_id", UUID.class));
                    m.put("type", rs.getString("type"));
                    m.put("txid", rs.getString("txid"));
                    m.put("merchant_id", rs.getObject("merchant_id", UUID.class));
                    m.put("payload", rs.getString("payload"));
                    m.put("occurred_at", rs.getObject("occurred_at", Instant.class));
                    m.put("created_at", rs.getObject("created_at", Instant.class));
                    return m;
                })
                .single();
        assertThat(row.get("type")).isEqualTo("payment.confirmed");
        assertThat(row.get("txid")).isEqualTo(txid);
        assertThat(row.get("merchant_id")).isEqualTo(MERCHANT);

        // Redelivery dedupe (IT2 analogue): same eventId delivered again → ack, no second row
        UUID eventId = (UUID) row.get("event_id");
        String rawEnvelope = buildEnvelopeWithEventId(eventId, "payment.confirmed", txid, 10000, 100, endToEndId);
        boolean ack = ingestion.processMessage(rawEnvelope);
        assertThat(ack).as("redelivery must ack").isTrue();
        assertThat(notificationCount()).as("redelivery must not create second row").isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private long notificationCount() {
        return jdbc.sql("select count(*) from notifications.notification").query(Long.class).single();
    }

    private String createPayment(String idemKey) throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", "{\"amount\":10000,\"description\":\"Notifs loop IT\",\"expiresIn\":\"PT30M\"}",
                authHeaders(idemKey));
        assertThat(resp.statusCode())
                .withFailMessage(() -> "create failed: " + resp.statusCode() + " body=" + resp.body())
                .isEqualTo(201);
        return parse(resp).at("/txid").asText();
    }

    private String paymentStatus(String txid) {
        return jdbc.sql("select status from payments.payments where txid=:t")
                .param("t", txid).query(String.class).single();
    }

    private String confirmedEnvelope(String txid, String endToEndId, int amount) {
        return envelope("payment.confirmed", txid, amount, 100, endToEndId);
    }

    private String envelope(String type, String txid) {
        return envelope(type, txid, 0, 0, "E9040381234567890123456789012345");
    }

    private String envelope(String type, String txid, int amount, int fee, String endToEndId) {
        int net = amount - fee;
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"fee\":" + fee + ",\"net\":" + net
                + ",\"late\":false,\"txid\":\"" + txid
                + "\",\"endToEndId\":\"" + endToEndId + "\"}}";
    }

    private String buildEnvelopeWithEventId(UUID eventId, String type, String txid, int amount, int fee, String endToEndId) {
        int net = amount - fee;
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"fee\":" + fee + ",\"net\":" + net
                + ",\"late\":false,\"txid\":\"" + txid
                + "\",\"endToEndId\":\"" + endToEndId + "\"}}";
    }

    private String confirmedBody(String txid, String endToEndId, int amount) {
        return "{\"eventId\":\"psp-evt-1\",\"type\":\"payment.confirmed"
                + "\",\"txid\":\"" + txid + "\",\"endToEndId\":\"" + endToEndId
                + "\",\"amount\":" + amount + ",\"paidAt\":\"" + PAID_AT + "\"}";
    }

    private HttpResponse<String> sendWebhook(String ts, String body) throws Exception {
        return sendWebhook(ts, body, sign(ts, body));
    }

    private HttpResponse<String> sendWebhook(String ts, String body, String sig) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/webhooks/psp"))
                .header("Content-Type", "application/json")
                .header("X-PSP-Timestamp", ts)
                .header("X-PSP-Signature", sig)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> authHeaders(String idemKey) {
        return Map.of(
                "Authorization", "Bearer " + rawKey,
                "Content-Type", "application/json",
                "Idempotency-Key", idemKey,
                "X-Request-Id", "req-" + idemKey);
    }

    private JsonNode parse(HttpResponse<String> resp) throws IOException {
        return MAPPER.readTree(resp.body());
    }

    private String sign(String ts, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized void ensureTopology() {
        if (notifsUrl != null) {
            return;
        }
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
        sns = SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
        String dlqArn = createFifoQueue(sqs, "dargent-payments-notifications-dlq-ml.fifo", null, null);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
        notifsUrl = createFifoQueue(sqs, "dargent-payments-notifications-ml.fifo", redrive, dlqArn);
        String notifsArn = arnOf(notifsUrl);
        topicArn = sns.createTopic(r -> r.name("dargent-payments-events-notifications-ml.fifo")
                .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false"))).topicArn();
        // RawMessageDelivery: the ledger consumer passes msg.body() straight to EventIngestionUseCase
        // (§5.3), so the SNS→SQS edge must deliver the raw envelope, not the SNS wrapper.
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(notifsArn)
                .attributes(Map.of("RawMessageDelivery", "true")));
    }

    private static String createFifoQueue(SqsClient client, String name, String redrive, String dlqArn) {
        Map<QueueAttributeName, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        if (redrive != null) {
            attrs.put(QueueAttributeName.REDRIVE_POLICY, redrive);
        }
        return client.createQueue(r -> r.queueName(name).attributes(attrs)).queueUrl();
    }

    private static String arnOf(String url) {
        return sqs.getQueueAttributes(r -> r.queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    @TestConfiguration
    static class NotificationsTestConfig {

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(
                            "classpath:db/migration/payments",
                            "classpath:db/migration/ledger",
                            "classpath:db/migration/notifications"
                    )
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

        @Bean
        SqsClient notifsTestSqsClient() {
            return sqs;
        }

        @Bean
        SqsNotificationConsumer notifsConsumer(NotificationIngestionUseCase ingestion, SqsClient notifsTestSqsClient) {
            return new SqsNotificationConsumer(notifsTestSqsClient, notifsUrl, 10, 600000, ingestion);
        }

        @Bean
        PspStub pspStub() {
            return new PspStub();
        }

        @Bean
        HttpServer pspServer(PspStub psp) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/cobs", psp::handle);
            server.start();
            return server;
        }

        @Bean
        @Primary
        PspPort pspTestPort(HttpServer server, PspStub psp) {
            int port = server.getAddress().getPort();
            return new SimulatorChargeAdapter("http://127.0.0.1:" + port, 3, Duration.ofMillis(20), psp::sleeper);
        }
    }

    static final class PspStub {
        enum Mode { SUCCESS, FAIL }

        volatile Mode mode = Mode.SUCCESS;

        void reset() {
            mode = Mode.SUCCESS;
        }

        long sleeper() {
            return 0L;
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            byte[] respBody;
            int status;
            if ("POST".equals(exchange.getRequestMethod()) && "/cobs".equals(path)) {
                status = 200;
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String txid = extractTxid(requestBody);
                respBody = ("{\"txid\":\"" + txid + "\",\"expiresAt\":\"" + PAID_AT
                        + "\",\"endToEndId\":\"E2E-1\",\"brcode\":\"000201-terribly-long-brcode\"}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                respBody = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, respBody.length);
            exchange.getResponseBody().write(respBody);
            exchange.close();
        }

        private String extractTxid(String body) {
            int i = body.indexOf("\"txid\"");
            int start = body.indexOf("\"", i + 7) + 1;
            int end = body.indexOf("\"", start);
            return body.substring(start, end);
        }
    }
}
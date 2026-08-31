package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IT5 (E6 §7, the M2 anchor): the wiring from a real HTTP create → webhook confirm → relay
 * {@code runOnce()} → publish to the (LocalStack) FIFO topic → delivery to the subscribed FIFO
 * queue, with per-payment ordering (MessageGroupId = txid) and idempotency (MessageDeduplicationId =
 * eventId). The relay beans run under Spring (relay enabled), the scheduler is defanged with a 10 min
 * poll so the test drives {@code runOnce()} deterministically. SNS→SQS delivery is awaited via the
 * SDK's own FIFO long-poll ({@code waitTimeSeconds}) — an SQS wait, never a sleep.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, OutboxDeliveryE2EIT.DeliveryTestConfig.class},
    properties = {
        "dargent.relay.enabled=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class OutboxDeliveryE2EIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_NAME = "dargent-payments-events-e2e.fifo";
    private static final String QUEUE_NAME = "dargent-payments-notify-e2e.fifo";
    private static final String DLQ_NAME = "dargent-payments-notify-dlq-e2e.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    // Far-future fixed clock: writer rows land with DB DEFAULT now() (real time), and the relay
    // claims with next_attempt_at <= clock.instant() — a future clock keeps every seeded row
    // immediately due without touching rows (harness rule: no row fiddling to fake eligibility).
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final long FIXED_NOW_SECS = FIXED_CLOCK.instant().getEpochSecond();
    private static final String PAID_AT = FIXED_CLOCK.instant().plusSeconds(120).toString();
    private static final String SECRET = "dev-only-secret";
    private static final String TYPE = "payment.confirmed";
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
    private static String topicArn;
    private static String notifyUrl;
    private static String notifyArn;
    private static String dlqArn;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PspStub psp;

    @Autowired
    OutboxDeliveryUseCase relay;

    @Autowired
    OutboxDeliveryUseCase.Policy relayPolicy;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    /**
     * Resolves the AWS-facing environment from the LocalStack container before the relay beans are
     * built (static containers are already started by Testcontainers at this point). The ARN/URL
     * placeholders come from the provisioned topology (spec §4.1, §5.2) — spec §5.2: asserted, never
     * assumed.
     */
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
    }

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        psp.reset();
    }

    /**
     * The M2 (milestone 2) anchor test: a payment created over the real HTTP API, confirmed by a
     * signed webhook, and — with the relay enabled — the {@code payment.confirmed} envelope lands on
     * the FIFO queue after a synchronous {@code runOnce()}, with MessageGroupId = txid and
     * MessageDeduplicationId = envelope eventId (E6 §5.1 step 3, §5.2).
     */
    @Test
    void confirmed_payment_reaches_the_fifo_queue_with_group_and_dedup_ids() throws Exception {
        String txid = createPayment("delivery-happy-01");
        String endToEndId = "E9040381234567890123456789012345";

        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), confirmedBody(txid, endToEndId, 10000));

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("processed");
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");
        long outboxRows = jdbc.sql(
                "select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single();
        assertThat(outboxRows).isEqualTo(1);

        // M2 anchor: drive the relay deterministically (scheduler defanged to 10 min poll).
        // The create step already queued payment.created — the batch delivers both rows.
        int delivered = relay.runOnce(relayPolicy.batchSize());
        assertThat(delivered).isEqualTo(2);

        Object[] row = jdbc.sql(
                "select status, attempt_count, published_at from payments.outbox "
                        + "where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getTimestamp(3)})
                .single();
        assertThat(row[0]).isEqualTo("SENT");
        assertThat(row[1]).isEqualTo(1);
        assertThat(((java.sql.Timestamp) row[2]).toInstant()).isEqualTo(FIXED_CLOCK.instant());

        // SNS -> SQS: FIFO long-poll (SQS's own wait, not a sleep); drain until the confirmed
        // envelope lands (created shares the same MessageGroupId and may be delivered first).
        var received = receiveUntilConfirmed(txid);
        JsonNode envelope = received.envelope();
        assertThat(envelope.path("type").asText()).isEqualTo("payment.confirmed");
        assertThat(envelope.path("version").asInt()).isEqualTo(1);
        assertThat(envelope.path("aggregateId").asText()).isEqualTo(txid);
        assertThat(envelope.path("payload").path("amount").asLong()).isEqualTo(10000L);
        assertThat(envelope.path("payload").path("fee").asLong()).isEqualTo(100L);
        assertThat(envelope.path("payload").path("net").asLong()).isEqualTo(9900L);
        assertThat(envelope.path("payload").path("late").asBoolean()).isFalse();
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        assertThat(eventId.version()).isEqualTo(4); // §5.5: envelope eventId stays v4

        assertThat(received.groupId()).isEqualTo(txid);
        assertThat(received.dedupeId()).isEqualTo(eventId.toString());
    }

    // ------------------------------------------------------------------ helpers

    private String createPayment(String idemKey) throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", "{\"amount\":10000,\"description\":\"Delivery IT\",\"expiresIn\":\"PT30M\"}",
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

    private record Received(JsonNode envelope, String groupId, String dedupeId) {}

    /**
     * Drains the notify queue with bounded FIFO long-polls until the {@code payment.confirmed}
     * message arrives (the {@code payment.created} sibling shares the group and may land first).
     * Each poll is SQS's own FIFO wait — never a sleep.
     */
    private Received receiveUntilConfirmed(String txid) {
        for (int attempt = 0; attempt < 6; attempt++) {
            var resp = sqs.receiveMessage(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                    .queueUrl(notifyUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .attributeNames(QueueAttributeName.ALL)
                    .build());
            for (var m : resp.messages()) {
                String subject = subjectOf(m.body());
                if (!"payment.confirmed".equals(subject)) {
                    continue; // drain the created sibling
                }
                JsonNode body;
                try {
                    body = MAPPER.readTree(m.body());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                JsonNode envelope;
                try {
                    envelope = MAPPER.readTree(body.path("Message").asText());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                Map<MessageSystemAttributeName, String> attrs = m.attributes();
                return new Received(envelope,
                        attrs.get(MessageSystemAttributeName.MESSAGE_GROUP_ID),
                        attrs.get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID));
            }
        }
        throw new AssertionError("payment.confirmed never arrived on the notify queue for " + txid);
    }

    private String subjectOf(String messageBody) {
        try {
            return MAPPER.readTree(messageBody).path("Subject").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<String> sendWebhook(String ts, String body) throws Exception {
        return postWebhook(ts, body, sign(ts, body));
    }

    private HttpResponse<String> postWebhook(String ts, String body, String sig) throws Exception {
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

    private JsonNode parse(String body) throws IOException {
        return MAPPER.readTree(body);
    }

    private JsonNode parse(HttpResponse<String> resp) throws IOException {
        return MAPPER.readTree(resp.body());
    }

    private String confirmedBody(String txid, String endToEndId, int amount) {
        return "{\"eventId\":\"psp-evt-1\",\"type\":\"" + TYPE
                + "\",\"txid\":\"" + txid + "\",\"endToEndId\":\"" + endToEndId
                + "\",\"amount\":" + amount + ",\"paidAt\":\"" + PAID_AT + "\"}";
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

    // ------------------------------------------------------------------ context wiring

    private static synchronized void ensureTopology() {
        if (topicArn != null) {
            return;
        }
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(REGION))
                .build();
        sns = SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
                .region(Region.of(REGION))
                .build();
        dlqArn = createFifoQueue(sqs, DLQ_NAME, null);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
        notifyUrl = createFifoQueue(sqs, QUEUE_NAME, redrive);
        notifyArn = queueArn(sqs, notifyUrl);
        topicArn = sns.createTopic(r -> r.name(TOPIC_NAME)
                .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false"))).topicArn();
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(notifyArn));
    }

    private static String createFifoQueue(SqsClient client, String name, String redrive) {
        Map<QueueAttributeName, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        if (redrive != null) {
            attrs.put(QueueAttributeName.REDRIVE_POLICY, redrive);
        }
        return client.createQueue(r -> r.queueName(name).attributes(attrs)).queueUrl();
    }

    private static String queueArn(SqsClient client, String url) {
        return client.getQueueAttributes(r -> r.queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    @TestConfiguration
    static class DeliveryTestConfig {

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
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] respBody;
            int status;
            if ("POST".equals(method) && "/cobs".equals(path)) {
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
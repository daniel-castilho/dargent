package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.adapter.out.messaging.SqsEventConsumer;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

/**
 * JSON-log correlation IT (E11 S1 remediation).
 *
 * <p>Boot's ECS console encoding lives in the Boot appender; this IT re-encodes the events captured
 * by {@link CapturingAppender} through {@link EcsLogContext} so every assertion runs against the real
 * ECS wire format (same encoder + formatter the prod console uses).
 *
 * <p>Legs (Block 2 commissioning, binding order):
 * (a) correlation fields — the echoed X-Request-Id appears on the create intake line; txid and
 * merchant_id appear where the context holds them, on the formatted wire;
 * (b) end-to-end request_id — a signed webhook confirm carries X-Request-Id through
 * WebhookController → WebhookIntakeUseCase → outbox envelope → relay → SQS → ledger ingest, and all
 * the emitted ECS lines carry the SAME request_id;
 * (c) scrubbing — over ALL captured formatted lines, no raw API key, no Authorization/Bearer value,
 * and no DB password (the real in-use value, Q13 ruling).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, JsonLogCorrelationIT.PspTestConfig.class},
    properties = {
        "spring.profiles.active=prod",
        "management.server.port=9090",
        "DARGENT_DB_PASSWORD=prod-test-password-that-is-at-least-32-chars-long",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "PSP_BASE_URL=http://psp-stub:8090",
        "PSP_WEBHOOK_SECRET=prod-test-webhook-secret-that-is-long-enough",
        "dargent.psp.webhook-secret=prod-test-webhook-secret-that-is-long-enough",
        "dargent.relay.enabled=true"
    }
)
@Testcontainers
class JsonLogCorrelationIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_NAME = "dargent-payments-events-corr.fifo";
    private static final String LEDGER_QUEUE = "dargent-payments-ledger-corr.fifo";
    private static final String LEDGER_DLQ = "dargent-payments-ledger-dlq-corr.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SECRET = "prod-test-webhook-secret-that-is-long-enough";
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
    private static String ledgerUrl;
    private static String topicArn;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Autowired
    OutboxDeliveryUseCase relay;

    @Autowired
    OutboxDeliveryUseCase.Policy relayPolicy;

    @Autowired
    SqsEventConsumer ledgerConsumer;

    @LocalServerPort
    int mainPort;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @DynamicPropertySource
    static void awsEnvironment(DynamicPropertyRegistry registry) {
        ensureTopology();
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SNS).toString());
        registry.add("AWS_REGION", () -> REGION);
        registry.add("DARGENT_EVENTS_TOPIC_ARN", () -> topicArn);
        registry.add("DARGENT_EVENTS_PUBLISH_TIMEOUT_MS", () -> "2000");
        registry.add("DARGENT_RELAY_BATCH", () -> "32");
        registry.add("DARGENT_RELAY_WORKERS", () -> "2");
        registry.add("DARGENT_RELAY_POLL_MS", () -> "600000");
        registry.add("DARGENT_OUTBOX_RETENTION_DAYS", () -> "7");
    }

    @BeforeEach
    void setUp() throws Exception {
        CapturingAppender.clear();
        baseUrl = "http://localhost:" + mainPort;
        jdbc.sql("truncate ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.settlements, ledger.audit_log, "
                + "payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
    }

    /** Leg (a): the echoed X-Request-Id is a field of the intake line; txid and merchant_id appear on the wire. */
    @Test
    void legA_echoedRequestId_and_txid_and_merchantId_onTheWire() throws Exception {
        // Given
        String requestId = "req-correlation-01";
        String idemKey = "idem-correlation-01";

        // When
        var resp = post("/v1/payments",
                "{\"amount\":10000,\"description\":\"Order #123\",\"expiresIn\":\"PT30M\"}",
                Map.of(
                        "Authorization", "Bearer " + rawKey,
                        "Content-Type", "application/json",
                        "Idempotency-Key", idemKey,
                        "X-Request-Id", requestId
                ));

        // Then
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue("X-Request-Id")).contains(requestId);
        String txid = parse(resp).at("/txid").asText();

        List<JsonNode> lines = capturedJsonLines();

        // Intake line: echoed requestId field + endpoint + merchant id in the message
        JsonNode intake = find(
                lines,
                l -> l.path("requestId").asText().equals(requestId)
                        && l.path("message").asText().contains("Payment create request"));
        assertThat(intake).isNotNull();
        assertThat(intake.path("message").asText()).contains("POST /v1/payments");
        assertThat(intake.path("message").asText()).contains(MERCHANT.toString());
        assertThat(intake.path("message").asText()).contains("amount_cents=10000");

        // Result line: same requestId, txid as a field, status in the message (S2 facts on the wire)
        JsonNode result = find(
                lines,
                l -> l.path("requestId").asText().equals(requestId)
                        && l.path("message").asText().contains("Payment create result"));
        assertThat(result).isNotNull();
        assertThat(result.path("txid").asText()).isEqualTo(txid);
        assertThat(result.path("message").asText()).contains("status=PENDING");

        // DB complement: outbox row carries the request_id
        var ob = jdbc.sql(
                "select type, request_id from payments.outbox where aggregate_id=:t")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2)})
                .list();
        assertThat(ob).hasSize(1);
        assertThat(ob.get(0)[0]).isEqualTo("payment.created");
        assertThat(ob.get(0)[1]).isEqualTo(requestId);
    }

    /** Leg (b): a signed webhook confirm carries X-Request-Id through outbox envelope → relay → SQS → ledger ingest. */
    @Test
    void legB_webhookConfirm_requestId_flowsToRelayAndLedgerIngestLines() throws Exception {
        // Given: a pending payment created via the API (own X-Request-Id, must not leak into the confirm)
        String createReqId = "req-legb-create";
        var createResp = post("/v1/payments",
                "{\"amount\":10000,\"description\":\"Correlation leg b\"}",
                Map.of(
                        "Authorization", "Bearer " + rawKey,
                        "Content-Type", "application/json",
                        "Idempotency-Key", "idem-legb-create",
                        "X-Request-Id", createReqId
                ));
        assertThat(createResp.statusCode()).isEqualTo(201);
        String txid = parse(createResp).at("/txid").asText();

        // When: signed webhook confirm with a distinct X-Request-Id
        String confirmReqId = "req-legb-confirm";
        String body = "{\"eventId\":\"psp-evt-legb-1\",\"type\":\"payment.confirmed\",\"txid\":\"" + txid
                + "\",\"endToEndId\":\"E9040381234567890123456789012345\",\"amount\":10000,\"paidAt\":\"2027-01-01T12:02:00Z\"}";
        CapturingAppender.clear();
        var resp = sendWebhook(body, confirmReqId);
        assertThat(resp.statusCode()).isEqualTo(200);

        // Drive the relay deterministically, poll the ledger consumer (SQS long-poll, never a sleep).
        int relayed = relay.runOnce(relayPolicy.batchSize());
        assertThat(relayed).isGreaterThan(0);
        for (int i = 0; i < 8 && journalEntries() == 0; i++) {
            ledgerConsumer.runOnce();
        }
        assertThat(journalEntries()).as("confirmed should post a journal entry").isEqualTo(1);

        List<JsonNode> lines = capturedJsonLines();

        // Webhook intake line carries the confirm requestId (MDC field)
        JsonNode intake = find(
                lines,
                l -> l.path("requestId").asText().equals(confirmReqId)
                        && l.path("message").asText().contains("Webhook intake"));
        assertThat(intake).isNotNull();
        assertThat(intake.path("message").asText()).contains("payment.confirmed");
        assertThat(intake.path("message").asText()).contains(txid);

        // Relay publish line carries the same request_id (came from the outbox envelope — E5 read side)
        JsonNode relayLine = find(
                lines,
                l -> l.path("request_id").asText().equals(confirmReqId)
                        && l.path("type").asText().equals("payment.confirmed")
                        && l.path("message").asText().contains("OUTBOX publish ok"));
        assertThat(relayLine).isNotNull();

        // Ledger ingest line carries the same request_id (wire surface of the read side)
        JsonNode ingest = find(
                lines,
                l -> l.path("request_id").asText().equals(confirmReqId)
                        && l.path("type").asText().equals("payment.confirmed")
                        && l.path("message").asText().contains("LEDGER ingest"));
        assertThat(ingest).isNotNull();
        assertThat(ingest.path("merchant_id").asText()).isEqualTo(MERCHANT.toString());

        // The create request id must NOT have leaked into the confirm trail (scoped to the confirm
        // lines — the create's own payment.created trail legitimately carries its own request id).
        assertThat(count(lines, l -> (l.path("request_id").asText().equals(createReqId)
                || l.path("requestId").asText().equals(createReqId))
                && (l.path("type").asText().equals("payment.confirmed")
                        || l.path("message").asText().contains("Webhook intake")))).isZero();

        // DB complement: outbox payment.confirmed row carries the request_id; ledger posted the journal
        var ob = jdbc.sql(
                "select type, request_id from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2)})
                .list();
        assertThat(ob).hasSize(1);
        assertThat(ob.get(0)[1]).isEqualTo(confirmReqId);
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(9900);
    }

    /** Leg (c): scrubbing over ALL captured formatted lines — no raw key, no bearer value, no DB password. */
    @Test
    void legC_noSecretsInAnyEmittedLine() throws Exception {
        // When: a valid create with the raw key in Authorization
        int createRespStatus = post("/v1/payments",
                "{\"amount\":1000,\"description\":\"Scrub test\"}",
                Map.of(
                        "Authorization", "Bearer " + rawKey,
                        "Content-Type", "application/json",
                        "Idempotency-Key", "idem-scrub-01",
                        "X-Request-Id", "req-scrub-01"
                )).statusCode();
        assertThat(createRespStatus).isEqualTo(201);

        // an invalid-signature webhook (attack intake, raw body persisted)
        String invalidPayload = "{\"txid\":\"invalid\",\"status\":\"CONFIRMED\"}";
        post("/webhooks/psp", invalidPayload, Map.of("Content-Type", "application/json",
                "X-Request-Id", "req-scrub-02"));

        // and a valid signed webhook with a nonexistent txid (IGNORED path)
        String unknownTxid = "9KD4Z9X2Q7W1M5T3R6Y0A1B2D";
        String body = "{\"eventId\":\"psp-evt-scrub-1\",\"type\":\"payment.confirmed\",\"txid\":\"" + unknownTxid
                + "\",\"endToEndId\":\"E9040381234567890123456789012345\",\"amount\":1000,\"paidAt\":\"2027-01-01T12:02:00Z\"}";
        var ignored = sendWebhook(body, "req-scrub-03");
        assertThat(ignored.statusCode()).isEqualTo(200);

        String dbPassword = postgres.getPassword();
        String bearer = "Bearer " + rawKey;

        // Then: across every formatted wire line, no secret material occurs
        List<String> formatted = capturedFormattedLines();
        assertThat(formatted).isNotEmpty();
        assertThat(formatted).noneMatch(l -> l.contains(rawKey));
        assertThat(formatted).noneMatch(l -> l.contains(bearer));
        assertThat(formatted).noneMatch(l -> l.contains(dbPassword));
    }

    // ------------------------------------------------------------------ helpers

    private List<String> capturedFormattedLines() {
        return EcsLogContext.formatEcs(CapturingAppender.getEvents());
    }

    private List<JsonNode> capturedJsonLines() {
        return capturedFormattedLines().stream()
                .map(line -> {
                    try {
                        return MAPPER.readTree(line);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static JsonNode find(List<JsonNode> lines, java.util.function.Predicate<JsonNode> p) {
        return lines.stream().filter(p).findFirst().orElse(null);
    }

    private static long count(List<JsonNode> lines, java.util.function.Predicate<JsonNode> p) {
        return lines.stream().filter(p).count();
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendWebhook(String body, String requestId) throws Exception {
        String ts = String.valueOf(clock.instant().getEpochSecond());
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/webhooks/psp"))
                .header("Content-Type", "application/json")
                .header("X-PSP-Timestamp", ts)
                .header("X-PSP-Signature", sign(ts, body))
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
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

    private JsonNode parse(HttpResponse<String> resp) throws IOException {
        return MAPPER.readTree(resp.body());
    }

    private int journalEntries() {
        return jdbc.sql("select count(*) from ledger.journal_entries").query(int.class).single();
    }

    private long balance(String account) {
        Long b = jdbc.sql("select balance_cents from ledger.balances where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
        return b;
    }

    private static synchronized void ensureTopology() {
        if (ledgerUrl != null) {
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
        String dlqArn = createFifoQueue(sqs, LEDGER_DLQ, null, null);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
        ledgerUrl = createFifoQueue(sqs, LEDGER_QUEUE, redrive, dlqArn);
        String ledgerArn = arnOf(ledgerUrl);
        topicArn = sns.createTopic(r -> r.name(TOPIC_NAME)
                .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false"))).topicArn();
        // RawMessageDelivery so the consumer passes the envelope straight to EventIngestionUseCase.
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(ledgerArn)
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

    // ------------------------------------------------------------------ test config

    @Configuration
    static class PspTestConfig {

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
            return Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        Object ecsEnvironment(Environment environment) {
            EcsLogContext.registerSpringEnvironment(environment);
            return new Object();
        }

        @Bean
        SqsClient ledgerTestSqsClient() {
            return sqs;
        }

        @Bean
        SqsEventConsumer ledgerConsumer(io.dargent.ledger.application.EventIngestionUseCase ingestion,
                SqsClient ledgerTestSqsClient) {
            return new SqsEventConsumer(ledgerTestSqsClient, ledgerUrl, 10, 600000, ingestion);
        }

        @Bean
        PspStub pspStub() {
            return new PspStub();
        }

        @Bean
        com.sun.net.httpserver.HttpServer pspServer(PspStub psp) throws IOException {
            com.sun.net.httpserver.HttpServer server =
                    com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/cobs", psp::handle);
            server.start();
            return server;
        }

        @Bean
        @Primary
        PspPort pspTestPort(com.sun.net.httpserver.HttpServer server, PspStub psp) {
            int port = server.getAddress().getPort();
            return new SimulatorChargeAdapter("http://127.0.0.1:" + port, 3, Duration.ofMillis(20), psp::sleeper);
        }
    }
}
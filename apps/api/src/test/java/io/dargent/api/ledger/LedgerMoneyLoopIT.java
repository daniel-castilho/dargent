package io.dargent.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.adapter.out.messaging.SqsEventConsumer;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
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

/**
 * IT1–IT4 (E7 §7): the ledger money loop against PG16 + LocalStack, runOnce-driven with static creds
 * and zero sleeps. IT1 is the full M2 loop (HTTP create → webhook → relay runOnce → ledger consumer
 * runOnce → journal + 3 postings + balances + proof); IT2 redelivery dedupe; IT3 non-posting events;
 * IT4 corrupt → proof ok:false → rebuild → proof ok.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, LedgerMoneyLoopIT.MoneyLoopTestConfig.class},
    properties = {
        "dargent.relay.enabled=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class LedgerMoneyLoopIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_NAME = "dargent-payments-events-ml.fifo";
    private static final String LEDGER_QUEUE = "dargent-payments-ledger-ml.fifo";
    private static final String LEDGER_DLQ = "dargent-payments-ledger-dlq-ml.fifo";
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
    private static String ledgerUrl;
    private static String topicArn;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OutboxDeliveryUseCase relay;

    @Autowired
    OutboxDeliveryUseCase.Policy relayPolicy;

    @Autowired
    SqsEventConsumer ledgerConsumer;

    @Autowired
    EventIngestionUseCase ingestion;

    @Autowired
    LedgerReconciliationUseCase reconciliation;

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
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.settlements, ledger.audit_log, "
                + "payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'ledger-it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        psp.reset();
    }

    /** IT1 — M2 full loop: HTTP create → webhook → relay → ledger consumer → journal + 3 postings → proof ok. */
    @Test
    void confirmed_payment_flows_to_ledger_with_balanced_journal_and_proof() throws Exception {
        String txid = createPayment("ledger-loop-01");
        String endToEndId = "E9040381234567890123456789012345";

        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), confirmedBody(txid, endToEndId, 10000));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");

        // Drive the payments relay deterministically, then the ledger consumer runOnce. SNS→SQS
        // fan-out is asynchronous, so poll the consumer until the confirmed is posted (each cycle is
        // an SQS long-poll — an SQS wait, never a sleep).
        int relayed = relay.runOnce(relayPolicy.batchSize());
        assertThat(relayed).isGreaterThan(0);
        for (int i = 0; i < 8 && journalEntries() == 0; i++) {
            ledgerConsumer.runOnce();
        }
        assertThat(journalEntries()).as("confirmed should post a journal entry").isEqualTo(1);

        // Ledger events: created IGNORED (non-posting), confirmed POSTED.
        Map<String, String> statuses = jdbc.sql(
                "select type, status from ledger.events where txid = :t order by type")
                .param("t", txid).query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(statuses).containsEntry("payment.confirmed", "POSTED");

        // One journal entry with 3 postings.
        assertThat(journalEntries()).isEqualTo(1);
        assertThat(postings()).isEqualTo(3);

        // Balances: available +9900, fees +100, processing −10000 (credit-positive convention §5.2).
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(9900);
        assertThat(balance("fees:revenue")).isEqualTo(100);
        assertThat(balance("payments:processing")).isEqualTo(-10000);

        assertProofOk(3, 3);
    }

    /** IT2 — idempotent redelivery: same confirmed message twice → one journal row; second is a dedupe ack. */
    @Test
    void redelivered_confirmed_message_posts_once_and_second_is_a_duplicate_ack() {
        String txid = "ledger-redeliver-02";
        String raw = confirmedEnvelope(txid, "E9040381234567890123456789012345", 5000);

        assertThat(ingestion.processMessage(raw)).isTrue();
        assertThat(journalEntries()).isEqualTo(1);
        assertThat(postings()).isEqualTo(3);
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(4900);

        // Redelivery (same eventId) — ack, no second posting.
        assertThat(ingestion.processMessage(raw)).isTrue();
        assertThat(journalEntries()).isEqualTo(1);
        assertThat(postings()).isEqualTo(3);
        assertProofOk(3, 3);
    }

    /** IT3 — non-posting events: created/failed/unknown → events row IGNORED, zero postings, proof ok. */
    @Test
    void non_confirmed_events_are_ignored_without_postings_and_proof_stays_ok() {
        String txid = "ledger-nonpost-03";
        for (String type : java.util.List.of("payment.created", "payment.failed", "payment.unknown")) {
            assertThat(ingestion.processMessage(envelope(type, txid))).isTrue();
        }
        long ignored = jdbc.sql("select count(*) from ledger.events where status = 'IGNORED'")
                .query(Long.class).single();
        assertThat(ignored).isEqualTo(3);
        assertThat(journalEntries()).isZero();
        assertThat(postings()).isZero();
        assertProofOk(0, 0);
    }

    /** IT4 — proof & rebuild: post N events, corrupt one balance → ok:false, rebuild → ok again. */
    @Test
    void corrupt_balance_fails_proof_and_rebuild_restores_ok() {
        for (int i = 0; i < 3; i++) {
            assertThat(ingestion.processMessage(confirmedEnvelope(
                    "ledger-proof-" + i, "E9040381234567890123456789012345", 1000 + i * 10))).isTrue();
        }
        // 3 confirmed → 3 journal entries × 3 postings.
        assertThat(journalEntries()).isEqualTo(3);
        assertThat(postings()).isEqualTo(9);
        assertProofOk(3, 9);

        // Corrupt a single balance (test-local UPDATE, mirroring a projection drift).
        var proof = reconciliation.proof();
        assertThat(proof.ok()).isTrue();
        int rows = jdbc.sql("update ledger.balances set balance_cents = balance_cents + 7 "
                + "where account = :a")
                .param("a", "merchant:" + MERCHANT + ":available")
                .update();
        assertThat(rows).isEqualTo(1);

        var broken = reconciliation.proof();
        assertThat(broken.ok()).isFalse();
        assertThat(broken.firstDivergence()).contains("merchant:" + MERCHANT + ":available");

        reconciliation.rebuild(KEY_ID);
        assertProofOk(3, 9);

        long audit = jdbc.sql("select count(*) from ledger.audit_log where command = 'REBUILD'")
                .query(Long.class).single();
        assertThat(audit).isEqualTo(1);
    }

/** BD-15 guard IT: redelivery after posting failure resumes and posts exactly once.
     * Two legs (adjudicated Q1/Q2):
     *   Leg 1 (failure injection): trigger on journal_entries INSERT throws -> exception, row RECEIVED, 0 journal rows.
     *   Leg 2 (redelivery): drop trigger -> redeliver same message -> exactly-once resume (ack, 1 journal, 3 postings, proof ok).
     * @AfterEach drops trigger as safety net for container reuse.
     */
    @Test
    void redelivery_after_posting_failure_resumes_and_posts_exactly_once() throws Exception {
        String txid = "ledger-bd15-guard-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String raw = confirmedEnvelopeWithEventId(eventId, txid, "E9040381234567890123456789012345", 5000);
        String payloadJson = extractPayloadJson(raw);

        // --- Leg 1: failure injection via trigger on journal_entries INSERT ---
        String triggerName = "trg_fail_journal_insert";
        String funcName = "fail_journal_insert_once";
        String ddlCreate = "CREATE OR REPLACE FUNCTION " + funcName + "() RETURNS TRIGGER AS $$\n"
                + "BEGIN\n"
                + "    IF TG_OP = 'INSERT' THEN\n"
                + "        RAISE EXCEPTION 'SIMULATED_POSTING_FAILURE';\n"
                + "    END IF;\n"
                + "    RETURN NEW;\n"
                + "END;\n"
                + "$$ LANGUAGE plpgsql;\n"
                + "DROP TRIGGER IF EXISTS " + triggerName + " ON ledger.journal_entries;\n"
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON ledger.journal_entries\n"
                + "FOR EACH ROW EXECUTE FUNCTION " + funcName + "();";
        jdbc.sql(ddlCreate).update();

        try {
            // 1a. Manually insert event in RECEIVED state (simulating crash after event insert, before journal)
            String insertSql = """
                    INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                    VALUES (?, 'payment.confirmed', ?, ?, ?::jsonb, 'RECEIVED', 'Initial receipt')
                    """;
            jdbc.sql(insertSql).params(eventId, txid, MERCHANT, payloadJson).update();

            // Verify initial state: RECEIVED, zero journal rows
            String status = jdbc.sql("select status from ledger.events where txid = :t")
                    .param("t", txid).query(String.class).single();
            assertThat(status).isEqualTo("RECEIVED");
            assertThat(journalEntries()).isZero();
            assertThat(postings()).isZero();

            // 1b. First attempt: trigger fires, exception propagates (consumer catches and nacks)
            assertThatThrownBy(() -> ingestion.processMessage(raw))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("SIMULATED_POSTING_FAILURE");

            // Verify: row stays RECEIVED, zero journal rows, payment unaffected
            String statusAfterFail = jdbc.sql("select status from ledger.events where txid = :t")
                    .param("t", txid).query(String.class).single();
            assertThat(statusAfterFail).isEqualTo("RECEIVED");
            assertThat(journalEntries()).as("no journal rows on first attempt").isZero();
            assertThat(postings()).isZero();

            // --- Leg 2: drop trigger, redeliver same message -> exactly-once resume ---
            String ddlDrop = "DROP TRIGGER IF EXISTS " + triggerName + " ON ledger.journal_entries;";
            jdbc.sql(ddlDrop).update();

            // 2a. Redeliver the SAME message (same eventId) - should resume and post exactly once
            boolean ack2 = ingestion.processMessage(raw);
            assertThat(ack2).as("redelivery should ack and post exactly once").isTrue();

            // Verify: exactly ONE journal entry + 3 postings, balances incremented once, proof ok
            assertThat(journalEntries()).as("exactly one journal entry after resume").isEqualTo(1);
            assertThat(postings()).as("exactly three postings").isEqualTo(3);
            assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(4900);
            assertThat(balance("fees:revenue")).isEqualTo(100);
            assertThat(balance("payments:processing")).isEqualTo(-5000);
            assertProofOk(3, 3);

            // 3. Verify event status is now POSTED
            String finalStatus = jdbc.sql("select status from ledger.events where txid = :t")
                    .param("t", txid).query(String.class).single();
            assertThat(finalStatus).isEqualTo("POSTED");
        } finally {
            // Safety: ensure trigger is cleaned up even if test fails (container reuse safety)
            String ddlCleanup = "DROP TRIGGER IF EXISTS " + triggerName + " ON ledger.journal_entries;";
            jdbc.sql(ddlCleanup).update();
        }
    }

    private String confirmedEnvelopeWithEventId(UUID eventId, String txid, String endToEndId, int amount) {
        int net = amount - 100;
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"payment.confirmed"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"fee\":100,\"net\":" + net
                + ",\"late\":false,\"txid\":\"" + txid
                + "\",\"endToEndId\":\"" + endToEndId + "\"}}";
    }

    private String extractPayloadJson(String envelopeJson) {
        // Extract the "payload" object from the envelope JSON
        int payloadStart = envelopeJson.indexOf("\"payload\":{");
        if (payloadStart == -1) {
            throw new IllegalStateException("No payload in envelope: " + envelopeJson);
        }
        payloadStart += 10; // length of "\"payload\":"
        int depth = 0;
        int end = payloadStart;
        for (int i = payloadStart; i < envelopeJson.length(); i++) {
            char c = envelopeJson.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        return envelopeJson.substring(payloadStart, end);
    }

    // ------------------------------------------------------------------ helpers

    private void assertProofOk(long accounts, long postings) {
        var proof = reconciliation.proof();
        assertThat(proof.ok())
                .withFailMessage("proof failed: %s", proof.firstDivergence())
                .isTrue();
        assertThat(proof.accountsChecked()).isEqualTo(accounts);
        assertThat(proof.postingsChecked()).isEqualTo(postings);
    }

    private long journalEntries() {
        return jdbc.sql("select count(*) from ledger.journal_entries").query(Long.class).single();
    }

    private long postings() {
        return jdbc.sql("select count(*) from ledger.postings").query(Long.class).single();
    }

    private long balance(String account) {
        return jdbc.sql("select balance_cents from ledger.balances where account = :a")
                .param("a", account).query(Long.class).single();
    }

    private String createPayment(String idemKey) throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", "{\"amount\":10000,\"description\":\"Ledger loop IT\",\"expiresIn\":\"PT30M\"}",
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

    private String confirmedBody(String txid, String endToEndId, int amount) {
        return "{\"eventId\":\"psp-evt-1\",\"type\":\"payment.confirmed"
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
        // RawMessageDelivery: the ledger consumer passes msg.body() straight to EventIngestionUseCase
        // (§5.3), so the SNS→SQS edge must deliver the raw envelope, not the SNS wrapper.
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

    @TestConfiguration
    static class MoneyLoopTestConfig {

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
        SqsClient ledgerTestSqsClient() {
            return sqs;
        }

        @Bean
        SqsEventConsumer ledgerConsumer(EventIngestionUseCase ingestion, SqsClient ledgerTestSqsClient) {
            return new SqsEventConsumer(ledgerTestSqsClient, ledgerUrl, 10, 600000, ingestion);
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

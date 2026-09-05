package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.payments.adapter.out.messaging.DlqDepthPoller;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter;
import io.dargent.payments.application.OutboxDeliveryUseCase;
import io.dargent.payments.application.PaymentsMetrics;
import io.dargent.payments.domain.port.out.EventPublisher;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import io.dargent.payments.domain.port.out.PspPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.transaction.support.TransactionTemplate;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * E11 S4 metrics scrape IT (spec §6): one boot, all legs, one scrape.
 *
 * <p>Drives every one of the 8 frozen series (observability.md §3) through the REAL HTTP surface
 * and the REAL schedulers (deterministic {@code runOnce()} calls — house pattern), then scrapes
 * {@code /actuator/prometheus} on the isolated management port and asserts each series is
 * present, with its frozen tag vocabulary, and non-zero.
 *
 * <p>Determinism: fixed {@link MutableClock} (2027-01-01T12:00:00Z) shared by app and test seeds;
 * boot-time schedulers run with huge intervals (never fire during the test — the fixed-delay
 * schedulers' first run is one interval away, the relay's fixed-rate start time is the injected
 * 2027 clock instant); the relay is driven explicitly via {@link OutboxDeliveryUseCase#runOnce(int)};
 * the broken-relay exhaustion ladder advances on the injected clock — zero sleeps (AGENTS §5.3).
 *
 * <p>Frozen contract under test (names + tag vocabularies, all exercised):
 * <ol>
 *   <li>{@code dargent_payments_transitions_total{from,to,outcome}}</li>
 *   <li>{@code dargent_outbox_lag_seconds} (gauge, ≥ 600 s from a seeded due row)</li>
 *   <li>{@code dargent_outbox_attempts_total{result=sent|failed|exhausted}}</li>
 *   <li>{@code dargent_dlq_messages{queue}} (gauge, 1 seeded DLQ message)</li>
 *   <li>{@code dargent_reconciler_confirmations_total{outcome=confirm|resurrect}}</li>
 *   <li>{@code dargent_webhook_signature_failures_total{reason=invalid|expired}</li>
 *   <li>{@code dargent_idempotency_events_total{kind=replayed|conflict|in_flight}}</li>
 *   <li>{@code dargent_refunds_rejected_total{code=not_refundable|exceeds_remaining}}</li>
 * </ol>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, MetricsScrapeIT.MetricsTestConfig.class},
    properties = {
        "spring.profiles.active=prod",
        "management.server.port=9090",
        "DARGENT_DB_PASSWORD=prod-test-password-that-is-at-least-32-chars-long",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "PSP_BASE_URL=http://psp-stub:8090",
        "PSP_WEBHOOK_SECRET=prod-test-webhook-secret-that-is-long-enough",
        "dargent.psp.webhook-secret=prod-test-webhook-secret-that-is-long-enough",
        "dargent.relay.enabled=true",
        "dargent.ledger.consumer.enabled=true",
        "DARGENT_RECONCILER_ENABLED=true",
        "DARGENT_EXPIRATION_ENABLED=true",
        // Huge intervals: scheduler beans boot (for direct runOnce()) but never fire on their own
        "DARGENT_RECONCILER_SCAN_MS=3600000",
        "DARGENT_EXPIRATION_INTERVAL_MS=3600000",
        "DARGENT_RELAY_POLL_MS=3600000",
        "DARGENT_RELAY_BATCH=32",
        "DARGENT_RELAY_WORKERS=2",
        "DARGENT_RELAY_MAX_ATTEMPTS=3",
        "DARGENT_OUTBOX_RETENTION_DAYS=7",
        "DARGENT_EVENTS_PUBLISH_TIMEOUT_MS=2000",
        "DARGENT_RECONCILER_GIVE_UP_HOURS=72",
        "DARGENT_RECONCILER_BACKOFF_MS=60000,300000,900000,3600000"
    }
)
@Testcontainers
class MetricsScrapeIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_NAME = "dargent-payments-events-metrics.fifo";
    private static final String LEDGER_QUEUE = "dargent-payments-ledger-metrics.fifo";
    private static final String LEDGER_DLQ = "dargent-payments-ledger-dlq-metrics.fifo";
    private static final String NOTIFS_QUEUE = "dargent-notifications-metrics.fifo";
    private static final String NOTIFS_DLQ = "dargent-notifications-dlq-metrics.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SECRET = "prod-test-webhook-secret-that-is-long-enough";
    private static final Instant START = Instant.parse("2027-01-01T12:00:00Z");
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
    private static String notifsUrl;
    private static String ledgerDlqUrl;
    private static String topicArn;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ReconciliationScheduler reconciliationScheduler;

    @Autowired
    ExpirationScheduler expirationScheduler;

    @Autowired
    OutboxDeliveryUseCase relay;

    @Autowired
    OutboxDeliveryUseCase.Policy relayPolicy;

    @Autowired
    DlqDepthPoller dlqDepthPoller;

    @Autowired
    OutboxEventStore outboxStore;

    @Autowired
    TransactionTemplate txTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PspStub psp;

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
        registry.add("DARGENT_LEDGER_QUEUE_URL", () -> ledgerUrl);
        registry.add("DARGENT_NOTIFS_QUEUE_URL", () -> notifsUrl);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + mainPort;
        psp.reset();
        clock.reset();
        jdbc.sql("truncate ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.settlements, ledger.audit_log, "
                + "payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.refunds, payments.audit_log, payments.payments, payments.api_keys "
                + "restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        // Refund balance guard reads the ledger projection: seed a comfortable available balance.
        jdbc.sql("insert into ledger.balances (account, balance_cents) values (:a, :c)")
                .param("a", "merchant:" + MERCHANT + ":available")
                .param("c", 1_000_000L)
                .update();
    }

    /**
     * Union scrape (Q16): drives all legs in dependency order, then one scrape must show every
     * frozen series with its frozen tag vocabulary and non-zero values. One test = one atomic scrape.
     */
    @Test
    void allEightFrozenSeries_present_nonZero_withFrozenTags_afterAllLegs() throws Exception {
        // ----------------------------------------------------------------- leg A: create + relay
        String txidMain = createPayment("idem-metrics-01", 10000, "Metrics leg A");
        createPayment("idem-metrics-01", 10000, "Metrics leg A");              // replayed
        createPaymentExpect("idem-metrics-01", 409, 1000, "Metrics conflict"); // conflict: different body
        String inFlightBody = "{\"amount\":10000,\"description\":\"Metrics in-flight\"}";
        seedInFlightRow("idem-metrics-inflight", fingerprintOf(inFlightBody));
        createPaymentBodyExpect("idem-metrics-inflight", 425, inFlightBody);   // in_flight

        int relayed = relay.runOnce(relayPolicy.batchSize());
        assertThat(relayed).isGreaterThan(0);

        // ------------------------------------------------- leg B: webhook confirm + signature failures
        postSignedWebhook(paymentConfirmedBody(txidMain, 10000));
        postWebhookExpiredTimestamp(paymentConfirmedBody(txidMain, 10000));
        postWebhookInvalidSignature("{\"eventId\":\"evt-bad\",\"type\":\"payment.confirmed\","
                + "\"txid\":\"" + txidMain + "\"}");

        // ---------------------------------------- leg C: reconciler confirm + resurrect + expire
        String txidConfirm = seedPendingPayment("RECCONFIRM", START.minusSeconds(60), START.plusSeconds(7200));
        psp.paidFor(txidConfirm);
        assertThat(reconciliationScheduler.runOnce()).isEqualTo(1);

        String txidResurrect = seedExpiredPayment("RECRESUR");
        psp.paidFor(txidResurrect);
        assertThat(reconciliationScheduler.runOnce()).isEqualTo(1);

        String txidReconExpire = seedPendingPayment("RECEXP", START.minusSeconds(60), START.minusSeconds(30));
        psp.expiredFor(txidReconExpire);
        assertThat(reconciliationScheduler.runOnce()).isEqualTo(1);

        // ------------------------------------------------------------------- leg D: expiration
        String txidExpiry = seedPendingPayment("EXPLEG", null, START.minusSeconds(30));
        assertThat(expirationScheduler.runOnce()).isEqualTo(1);

        // ------------------------------------------------------------------ leg E: refund legs
        postRefundExpect(txidExpiry, 5000, "idem-ref-notrefundable", 409);  // not_refundable (EXPIRED)
        String txidRefundBase = seedConfirmedPayment("REFBASE", 10000, 0);
        postRefundExpect(txidRefundBase, 20000, "idem-ref-exceeds", 409);  // exceeds_remaining
        postRefundExpect(txidRefundBase, 4000, "idem-ref-ok", 201);         // valid refund

        // ---------------------------------------- leg F: outbox failed + exhausted (broken relay)
        UUID brokenRow = seedOutboxRow("payment.created", UUID.randomUUID().toString(),
                txidMain, "req-metrics-broken", START.minusSeconds(30));
        OutboxDeliveryUseCase broken = brokenRelay();
        assertThat(broken.runOnce(32)).isZero(); // attempt 1 → failed
        clock.advance(Duration.ofSeconds(30));
        assertThat(broken.runOnce(32)).isZero(); // attempt 2 → failed
        clock.advance(Duration.ofMinutes(2));
        assertThat(broken.runOnce(32)).isZero(); // attempt 3 → EXHAUSTED
        assertThat(jdbc.sql("select status from payments.outbox where id = :id")
                .param("id", brokenRow).query(String.class).single()).isEqualTo("EXHAUSTED");

        // --------------------------------------------------------------------- leg G: lag gauge
        seedOutboxRow("payment.created", UUID.randomUUID().toString(),
                txidMain, "req-metrics-lag", START.minusSeconds(600)); // due 10 min ago → lag ≥ 600 s

        // ----------------------------------------------------------------------- leg H: DLQ gauge
        sqs.sendMessage(b -> b.queueUrl(ledgerDlqUrl)
                .messageBody("{\"seed\":\"metrics-dlq\"}")
                .messageGroupId(txidMain)
                .messageDeduplicationId(UUID.randomUUID().toString()));
        dlqDepthPoller.poll();

        // ======================================================================= scrape + asserts
        String scrape = scrapePrometheus();
        assertAllSeries(scrape);
    }

    // ============================================================================== assertions

    private void assertAllSeries(String scrape) {
        // 1. transitions — one assert per exercised vocabulary entry
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"none\"", "to=\"PENDING\"", "outcome=\"create\"");
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"PENDING\"", "to=\"CONFIRMED\"", "outcome=\"webhook_confirm\"");
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"PENDING\"", "to=\"CONFIRMED\"", "outcome=\"reconciler_confirm\"");
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"EXPIRED\"", "to=\"CONFIRMED\"", "outcome=\"reconciler_confirm\"");
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"PENDING\"", "to=\"EXPIRED\"", "outcome=\"reconciler_expire\"");
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"PENDING\"", "to=\"EXPIRED\"", "outcome=\"expiry\"");
        assertSeries(scrape, "dargent_payments_transitions_total",
                "from=\"CONFIRMED\"", "to=\"PARTIALLY_REFUNDED\"", "outcome=\"refund\"");

        // 2. outbox lag: seeded row due 10 minutes ago → ≥ 600 s
        assertGaugeAtLeast(scrape, "dargent_outbox_lag_seconds", 600.0);

        // 3. outbox attempts: sent (relay), failed (broken ×2), exhausted (broken ×1)
        assertSeries(scrape, "dargent_outbox_attempts_total", "result=\"sent\"");
        assertSeries(scrape, "dargent_outbox_attempts_total", "result=\"failed\"");
        assertSeries(scrape, "dargent_outbox_attempts_total", "result=\"exhausted\"");

        // 4. DLQ depth: 1 seeded message on the ledger DLQ
        assertGauge(scrape, "dargent_dlq_messages", "queue=\"" + LEDGER_DLQ + "\"", 1.0);

        // 5. reconciler confirmations: confirm + resurrect
        assertSeries(scrape, "dargent_reconciler_confirmations_total", "outcome=\"confirm\"");
        assertSeries(scrape, "dargent_reconciler_confirmations_total", "outcome=\"resurrect\"");

        // 6. webhook signature failures: invalid + expired
        assertSeries(scrape, "dargent_webhook_signature_failures_total", "reason=\"invalid\"");
        assertSeries(scrape, "dargent_webhook_signature_failures_total", "reason=\"expired\"");

        // 7. idempotency events: replayed + conflict + in_flight
        assertSeries(scrape, "dargent_idempotency_events_total", "kind=\"replayed\"");
        assertSeries(scrape, "dargent_idempotency_events_total", "kind=\"conflict\"");
        assertSeries(scrape, "dargent_idempotency_events_total", "kind=\"in_flight\"");

        // 8. refunds rejected: not_refundable + exceeds_remaining
        assertSeries(scrape, "dargent_refunds_rejected_total", "code=\"not_refundable\"");
        assertSeries(scrape, "dargent_refunds_rejected_total", "code=\"exceeds_remaining\"");
    }

    // ================================================================================ helpers

    private String createPayment(String idemKey, long amount, String description) throws Exception {
        return createPaymentExpect(idemKey, 201, amount, description);
    }

    private String createPaymentExpect(String idemKey, int expectedStatus, long amount,
            String description) throws Exception {
        return createPaymentBodyExpect(idemKey, expectedStatus,
                "{\"amount\":" + amount + ",\"description\":\"" + description + "\"}");
    }

    private String createPaymentBodyExpect(String idemKey, int expectedStatus, String body) throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/payments"))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(expectedStatus);
        return expectedStatus == 201 ? extractTxid(resp.body()) : null;
    }

    private static String extractTxid(String body) {
        int s = body.indexOf("\"txid\":\"") + 8;
        return body.substring(s, body.indexOf('"', s));
    }

    /** SHA-256 hex of the raw body — the same canonical fingerprint the controller computes. */
    private static String fingerprintOf(String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String paymentConfirmedBody(String txid, long amount) {
        return "{\"eventId\":\"psp-evt-" + UUID.randomUUID() + "\",\"type\":\"payment.confirmed\","
                + "\"txid\":\"" + txid + "\",\"endToEndId\":\"E9040381234567890123456789012345\","
                + "\"amount\":" + amount + ",\"paidAt\":\"" + START + "\"}";
    }

    private void postSignedWebhook(String body) throws Exception {
        String ts = String.valueOf(clock.instant().getEpochSecond());
        postWebhookRaw(body, ts, sign(ts, body), 200);
    }

    private void postWebhookExpiredTimestamp(String body) throws Exception {
        String ts = String.valueOf(clock.instant().getEpochSecond() - 3600); // 1 h stale
        postWebhookRaw(body, ts, sign(ts, body), 401);
    }

    private void postWebhookInvalidSignature(String body) throws Exception {
        String ts = String.valueOf(clock.instant().getEpochSecond());
        postWebhookRaw(body, ts, "deadbeef".repeat(8), 401);
    }

    private void postWebhookRaw(String body, String ts, String signature, int expectedStatus) throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/webhooks/psp"))
                .header("Content-Type", "application/json")
                .header("X-PSP-Timestamp", ts)
                .header("X-PSP-Signature", signature)
                .header("X-Request-Id", "req-metrics-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(expectedStatus);
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

    private void postRefundExpect(String txid, long amount, String idemKey, int expectedStatus) throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/payments/" + txid + "/refunds"))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":" + amount + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(expectedStatus);
    }

    // ======================================================================== seed helpers

    private void seedInFlightRow(String idemKey, String fingerprint) {
        jdbc.sql("""
                insert into payments.idempotency_keys
                    (merchant_id, idempotency_key, endpoint, request_fingerprint, state)
                values (:m, :k, 'POST /v1/payments', :fp, 'IN_FLIGHT')
                """)
                .param("m", MERCHANT)
                .param("k", idemKey)
                .param("fp", fingerprint)
                .update();
    }

    /**
     * Seeds a PENDING payment with the given reconcile schedule and expiry; returns txid.
     * Txid is {@code [A-Z0-9]{25}} (Bacen cap) — alphanumeric tag + UUID hex, no dashes.
     */
    private String seedPendingPayment(String tag, Instant nextReconcileAt, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String txid = (tag + UUID.randomUUID().toString().replace("-", ""))
                .toUpperCase().substring(0, 25);
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'metrics-it', 10000, 'PENDING', 0,
                    :expiresAt, null, null, null, false, 0, :created, null,
                    :nextReconcileAt, 0)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("created", java.sql.Timestamp.from(START.minusSeconds(3600)))
                .param("nextReconcileAt",
                        nextReconcileAt == null ? null : java.sql.Timestamp.from(nextReconcileAt))
                .update();
        return txid;
    }

    /**
     * Seeds an EXPIRED payment due for reconciliation (mirror of ReconcilerResurrectionIT's
     * seed): expires_at in the past, inside the 72 h give-up window, due now.
     */
    private String seedExpiredPayment(String tag) {
        UUID id = UUID.randomUUID();
        String txid = (tag + UUID.randomUUID().toString().replace("-", ""))
                .toUpperCase().substring(0, 25);
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'metrics-it', 10000, 'EXPIRED', 1,
                    :expiresAt, null, null, null, false, 0, :created, null,
                    :nextReconcileAt, 1)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("expiresAt", java.sql.Timestamp.from(START.minusSeconds(30)))
                .param("created", java.sql.Timestamp.from(START.minusSeconds(7200)))
                .param("nextReconcileAt", java.sql.Timestamp.from(START.minusSeconds(60)))
                .update();
        return txid;
    }

    /** Seeds a CONFIRMED payment (fee 100, net amount-100); returns txid. */
    private String seedConfirmedPayment(String tag, long amountCents, long refundedCents) {
        String txid = seedPendingPayment(tag, null, START.plusSeconds(7200));
        jdbc.sql("""
                update payments.payments set status = 'CONFIRMED', version = 1,
                    end_to_end_id = 'E9040381234567890123456789012345',
                    fee_cents = 100, net_cents = :net,
                    confirmed_at = :confirmed, refunded_cents = :refunded
                where txid = :t
                """)
                .param("net", amountCents - 100)
                .param("confirmed", java.sql.Timestamp.from(START))
                .param("refunded", refundedCents)
                .param("t", txid)
                .update();
        return txid;
    }

    /** Seeds a PENDING outbox row due at the given instant; returns its id. */
    private UUID seedOutboxRow(String type, String eventId, String txid, String requestId, Instant dueAt) {
        UUID id = UUID.randomUUID();
        String payload = ("{\"eventId\":\"" + eventId + "\",\"type\":\"payment.created\",\"version\":1,"
                + "\"aggregateId\":\"" + txid + "\",\"merchantId\":\"" + MERCHANT + "\","
                + "\"requestId\":\"" + requestId + "\",\"occurredAt\":\"" + START + "\","
                + "\"payload\":{\"txid\":\"" + txid + "\",\"merchantId\":\"" + MERCHANT + "\","
                + "\"amount\":10000}}");
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id,
                    status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'PENDING', 0, :due)
                """)
                .param("id", id)
                .param("agg", txid)
                .param("type", type)
                .param("payload", payload)
                .param("req", requestId)
                .param("due", java.sql.Timestamp.from(dueAt))
                .update();
        return id;
    }

    /** A relay with a throwing publisher sharing the app registry: failed/exhausted counters. */
    private OutboxDeliveryUseCase brokenRelay() {
        EventPublisher broken = (type, payload, eventId, aggregateId) -> {
            throw new IllegalStateException("metrics-it broken publisher");
        };
        return new OutboxDeliveryUseCase(
                outboxStore,
                broken,
                MAPPER,
                clock,
                new OutboxDeliveryUseCase.Policy(32, 2, 1000, 3,
                        Duration.ofSeconds(30), Duration.ofMinutes(5), 7),
                txTemplate,
                new PaymentsMetrics(meterRegistry));
    }

    // ================================================================================ scrape

    private String scrapePrometheus() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:9090/actuator/prometheus"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return resp.body();
    }

    private static final String NUMBER = "([0-9.]+(?:[eE][+-]?[0-9]+)?)";

    /** Asserts the counter series with the exact tag set is present and > 0. */
    private void assertSeries(String scrape, String name, String... tags) {
        String tagString = String.join(",", tags);
        Pattern p = Pattern.compile(Pattern.quote(name) + "\\{([^}]*)\\}\\s+" + NUMBER);
        Matcher m = p.matcher(scrape);
        while (m.find()) {
            String labels = m.group(1);
            boolean all = true;
            for (String tag : tags) {
                if (!labels.contains(tag)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                assertThat(Double.parseDouble(m.group(2)))
                        .as("series %s{%s} must be > 0", name, tagString)
                        .isGreaterThan(0);
                return;
            }
        }
        throw new AssertionError("Missing metric series " + name + " with tags " + tagString
                + "\nscrape excerpt:\n" + excerpt(scrape, name));
    }

    /** Asserts a gauge line {@code name value} (no tags) exists with value ≥ min. */
    private void assertGaugeAtLeast(String scrape, String name, double min) {
        Pattern p = Pattern.compile(Pattern.quote(name) + "(?:\\{[^}]*\\})?\\s+" + NUMBER);
        Matcher m = p.matcher(scrape);
        while (m.find()) {
            if (Double.parseDouble(m.group(1)) >= min) {
                return;
            }
        }
        throw new AssertionError("Gauge " + name + " never reached " + min
                + "\nscrape excerpt:\n" + excerpt(scrape, name));
    }

    /** Asserts a gauge line with the given tag exists and equals the expected value. */
    private void assertGauge(String scrape, String name, String tag, double expected) {
        Pattern p = Pattern.compile(Pattern.quote(name) + "\\{([^}]*)\\}\\s+" + NUMBER);
        Matcher m = p.matcher(scrape);
        while (m.find()) {
            if (m.group(1).contains(tag)) {
                assertThat(Double.parseDouble(m.group(2)))
                        .as("gauge %s{%s}", name, tag)
                        .isEqualTo(expected);
                return;
            }
        }
        throw new AssertionError("Missing gauge " + name + " with tag " + tag
                + "\nscrape excerpt:\n" + excerpt(scrape, name));
    }

    private static String excerpt(String scrape, String name) {
        StringBuilder sb = new StringBuilder();
        for (String line : scrape.split("\\n")) {
            if (line.startsWith(name)) {
                sb.append(line).append("\n");
            }
        }
        return sb.isEmpty() ? "(no lines for " + name + " in scrape)" : sb.toString();
    }

    // ======================================================================= topology helpers

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
        String ledgerDlqQueueUrl = createFifoQueue(sqs, LEDGER_DLQ, null);
        String notifsDlqQueueUrl = createFifoQueue(sqs, NOTIFS_DLQ, null);
        String ledgerDlqArn = arnOf(ledgerDlqQueueUrl);
        String notifsDlqArn = arnOf(notifsDlqQueueUrl);
        String ledgerRedrive = "{\"deadLetterTargetArn\":\"" + ledgerDlqArn + "\",\"maxReceiveCount\":\"5\"}";
        String notifsRedrive = "{\"deadLetterTargetArn\":\"" + notifsDlqArn + "\",\"maxReceiveCount\":\"5\"}";
        ledgerUrl = createFifoQueue(sqs, LEDGER_QUEUE, ledgerRedrive);
        notifsUrl = createFifoQueue(sqs, NOTIFS_QUEUE, notifsRedrive);
        ledgerDlqUrl = sqs.getQueueUrl(r -> r.queueName(LEDGER_DLQ)).queueUrl();
        String ledgerArn = arnOf(ledgerUrl);
        topicArn = sns.createTopic(r -> r.name(TOPIC_NAME)
                .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false"))).topicArn();
        // RawMessageDelivery so the consumer passes the envelope straight to the use case.
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(ledgerArn)
                .attributes(Map.of("RawMessageDelivery", "true")));
    }

    private static String createFifoQueue(SqsClient client, String name, String redrive) {
        Map<QueueAttributeName, String> attrs = new LinkedHashMap<>();
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

    // ================================================================================ PSP stub

    /** PSP stub with per-txid state: create serves the ChargeResult shape, GET serves cob state. */
    static final class PspStub {

        enum State { OPEN, PAID, EXPIRED }

        private final Map<String, State> states = new java.util.concurrent.ConcurrentHashMap<>();

        void reset() {
            states.clear();
        }

        void paidFor(String txid) {
            states.put(txid, State.PAID);
        }

        void expiredFor(String txid) {
            states.put(txid, State.EXPIRED);
        }

        long sleeper() {
            return 0L;
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] respBody;
            int status;
            try {
                if ("POST".equals(method) && "/cobs".equals(path)) {
                    status = 200;
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    String txid = extractTxid(requestBody);
                    states.putIfAbsent(txid, State.OPEN);
                    respBody = ("{\"txid\":\"" + txid + "\",\"expiresAt\":\"2027-01-01T13:00:00Z\","
                            + "\"endToEndId\":\"E2E-METRICS-1\",\"brcode\":\"000201-metrics-it-brcode\"}")
                            .getBytes(StandardCharsets.UTF_8);
                } else if ("GET".equals(method) && path.startsWith("/cobs/")) {
                    String txid = path.substring("/cobs/".length());
                    State state = states.getOrDefault(txid, State.OPEN);
                    String e2e = state == State.PAID ? "\"E00416968202009221504E2345678910\"" : "null";
                    String paidAt = state == State.PAID ? "\"2027-01-01T11:59:30Z\"" : "null";
                    status = 200;
                    respBody = ("{\"txid\":\"" + txid + "\",\"state\":\"" + state + "\",\"amountCents\":10000,"
                            + "\"expiresAt\":\"2027-01-01T13:00:00Z\",\"endToEndId\":" + e2e
                            + ",\"paidAt\":" + paidAt + "}")
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 404;
                    respBody = "{}".getBytes(StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                status = 500;
                respBody = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, respBody.length);
            exchange.getResponseBody().write(respBody);
            exchange.close();
        }

        private static String extractTxid(String body) {
            int i = body.indexOf("\"txid\"");
            int start = body.indexOf('"', i + 7) + 1;
            int end = body.indexOf('"', start);
            return body.substring(start, end);
        }
    }

    // ============================================================================ MutableClock

    /** A clock whose instant can be advanced by exact ladder rungs (no sleeps). */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        void reset() { this.now = START; }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    // ================================================================================ test config

    @Configuration
    static class MetricsTestConfig {

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
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        @Bean
        Object ecsEnvironment(Environment environment) {
            EcsLogContext.registerSpringEnvironment(environment);
            return new Object();
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
            return new SimulatorChargeAdapter("http://127.0.0.1:" + port, 3,
                    Duration.ofMillis(20), psp::sleeper);
        }
    }
}

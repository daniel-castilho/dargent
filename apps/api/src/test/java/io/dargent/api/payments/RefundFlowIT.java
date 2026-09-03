package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E8 S5 golden-vector refund IT (spec §7 scenario 12/23): confirm 100.00/fee 1.00 →
 * partial refund 40.00 → fee reversal 0.40 → ledger journal entries [3]+[4] exact postings
 * → available 59.40, processing 60.00, fees 0.60 → proof green → redelivery of refund.created
 * is an idempotent no-op → full refund to REFUNDED.
 * <p>
 * Follows the LedgerSettlementIT pattern: seeds the ledger via {@link EventIngestionUseCase},
 * drives the refund through the real HTTP surface, then asserts ledger balances and proof.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, RefundFlowIT.RefundTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.ledger.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class RefundFlowIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EventIngestionUseCase ingestion;

    @Autowired
    LedgerReconciliationUseCase reconciliation;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys, payments.refunds, "
                + "ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.audit_log restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
    }

    /** Generates a valid 25-char txid: prefix (3) + 22 chars from UUID. */
    private String txid(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 22);
    }

    /** S5 golden vector: confirm 100.00/fee 1.00 → refund 40.00 → journal [3]+[4] → available 59.40. */
    @Test
    void golden_vector_refund_40_percent_posts_34_and_balances() throws Exception {
        String txid = txid("REF");

        // 1. Seed the ledger available balance via a confirmed payment (100.00, fee 1.00).
        fundConfirmedPayment(txid, 10000, 100);

        // 2. Create the matching CONFIRMED payment row in payments.
        seedConfirmedPayment(txid, 10000, 100, 9900, 0);

        // 3. Refund 40.00 via HTTP.
        var resp = postRefund(txid, "refund-key-golden-01", "req-refund-golden-01", "{\"amount\":4000}");
        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = MAPPER.readTree(resp.body());

        // 4. Assert the refund representation.
        assertThat(body.at("/payment").asText()).isEqualTo(txid);
        assertThat(body.at("/amount").asLong()).isEqualTo(4000);
        assertThat(body.at("/feeReversal").asLong()).isEqualTo(40);   // floor(100 × 4000 / 10000)
        assertThat(body.at("/net").asLong()).isEqualTo(3960);
        assertThat(body.at("/status").asText()).isEqualTo("SUCCEEDED");

        // 5. Drive the ledger refund.created consumer (mirrors the stub consumer driving processMessage).
        // Use a fixed eventId so redelivery tests work correctly.
        String refundEventId = UUID.randomUUID().toString();
        boolean refundProcessed = ingestion.processMessage(refundEnvelopeWithEventId(refundEventId, txid, 4000, 40, 3960));
        assertThat(refundProcessed).isTrue();

        // Verify the refund event was actually POSTED (not IGNORED/REJECTED)
        String eventStatus = jdbc.sql("select status from ledger.events where event_id = ?")
                .param(UUID.fromString(refundEventId))
                .query(String.class)
                .optional()
                .orElse("NOT_FOUND");
        assertThat(eventStatus).as("refund event should be POSTED").isEqualTo("POSTED");

        // 6. Ledger postings [3]+[4] exact:
        //    [3] Dr available 4000 / Cr processing 4000
        //    [4] Dr fees:revenue 40 / Cr available 40
        //    Initial (after confirm): available 9900, processing -10000, fees:revenue 100.
        //    After refund: available 9900 - 4000 + 40 = 5940, processing -10000 + 4000 = -6000, fees 100 - 40 = 60.
        //    Balance convention: credits - debits per account.
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(5940);
        assertThat(balance("payments:processing")).isEqualTo(-6000);
        assertThat(balance("fees:revenue")).isEqualTo(60);
        assertProofOk();

        // 7. Redelivery of the same refund.created event is an idempotent no-op.
        // Reuse the SAME eventId to test consumer idempotency.
        assertThat(ingestion.processMessage(refundEnvelopeWithEventId(refundEventId, txid, 4000, 40, 3960))).isTrue();
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(5940);
        assertThat(balance("payments:processing")).isEqualTo(-6000);
        assertThat(balance("fees:revenue")).isEqualTo(60);
        assertThat(refundPostings(journalIdFor(txid))).isEqualTo(4); // 4 postings in one journal entry: [3]+[4]

        // 8. Refund remaining 60.00 → full REFUNDED.
        var full = postRefund(txid, "refund-key-full-01", "req-refund-full-01", "{\"amount\":6000}");
        assertThat(full.statusCode()).isEqualTo(201);
        JsonNode fullBody = MAPPER.readTree(full.body());
        assertThat(fullBody.at("/amount").asLong()).isEqualTo(6000);
        assertThat(fullBody.at("/feeReversal").asLong()).isEqualTo(60); // floor(100 × 6000 / 10000)
        assertThat(fullBody.at("/net").asLong()).isEqualTo(5940);

        assertThat(ingestion.processMessage(refundEnvelope(txid, 6000, 60, 5940))).isTrue();
        assertThat(paymentStatus(txid)).isEqualTo("REFUNDED");
        // Post-full-refund: available 0, processing 0, fees 0.
        assertThat(balance("merchant:" + MERCHANT + ":available")).isZero();
        assertThat(balance("payments:processing")).isZero();
        assertThat(balance("fees:revenue")).isZero();
        assertProofOk();
    }

    /** Refunding more than the remaining amount → 409 insufficient_refundable_amount. */
    @Test
    void refund_exceeding_remaining_is_409_and_changes_nothing() throws Exception {
        String txid = txid("EXC");
        fundConfirmedPayment(txid, 10000, 100);
        seedConfirmedPayment(txid, 10000, 100, 9900, 0);

        var resp = postRefund(txid, "refund-key-exceed-01", "req-refund-exceed-01", "{\"amount\":10001}");
        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(resp.body()).path("code").asText())
                .isEqualTo("refund_exceeds_remaining");

        // Nothing changed: payment still CONFIRMED, zero refunds, zero postings.
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");
        assertThat(refundCount(txid)).isZero();
        assertProofOk();
    }

    // ------------------------------------------------------------------ helpers

    /** Funds the ledger by ingesting a confirmed payment envelope (net amount - fee). */
    private void fundConfirmedPayment(String txid, long amount, long fee) {
        long net = amount - fee;
        assertThat(ingestion.processMessage(confirmedEnvelope(txid, amount, fee))).isTrue();
    }

    private String confirmedEnvelope(String txid, long amount, long fee) {
        long net = amount - fee;
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"payment.confirmed"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"fee\":" + fee + ",\"net\":" + net
                + ",\"late\":false,\"txid\":\"" + txid + "\"}}";
    }

    /** Builds the refund.created envelope mirroring the outbox payload shape. */
    private String refundEnvelope(String txid, long amount, long feeReversal, long net) {
        return refundEnvelopeWithEventId(UUID.randomUUID().toString(), txid, amount, feeReversal, net);
    }

    private String refundEnvelopeWithEventId(String eventId, String txid, long amount, long feeReversal, long net) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"refund.created"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-refund-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"feeRefund\":" + feeReversal + ",\"netRefund\":" + net
                + ",\"txid\":\"" + txid + "\",\"refundId\":\"" + UUID.randomUUID() + "\"}}";
    }

    /** Seeds a CONFIRMED payments row so the refund use case can act on it. */
    private void seedConfirmedPayment(String txid, long amount, long fee, long net, long refunded) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-02T10:00:00Z");
        Instant confirmedAt = Instant.parse("2026-09-02T10:05:00Z");
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'refund-it', :amount, 'CONFIRMED', 1,
                    :expiresAt, :e2e, :fee, :net, false, :refunded, :created, :confirmed, null, 0)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("amount", amount)
                .param("fee", fee)
                .param("net", net)
                .param("refunded", refunded)
                .param("e2e", "E00416968202009221504E2345678910")
                .param("expiresAt", java.sql.Timestamp.from(createdAt.plusSeconds(3600)))
                .param("created", java.sql.Timestamp.from(createdAt))
                .param("confirmed", java.sql.Timestamp.from(confirmedAt))
                .update();
    }

    private HttpResponse<String> postRefund(String txid, String idemKey, String requestId, String json) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/payments/" + txid + "/refunds"))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idemKey)
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private long balance(String account) {
        return jdbc.sql("select balance_cents from ledger.balances where account = :a")
                .param("a", account).query(Long.class).optional().orElse(0L);
    }

    private String paymentStatus(String txid) {
        return jdbc.sql("select status from payments.payments where txid = :t")
                .param("t", txid).query(String.class).single();
    }

    private long refundCount(String txid) {
        return jdbc.sql("select count(*) from payments.refunds where txid = :t")
                .param("t", txid).query(Long.class).single();
    }

    private long refundPostings(String journalId) {
        return jdbc.sql("select count(*) from ledger.postings where entry_id = :j")
                .param("j", UUID.fromString(journalId)).query(Long.class).single();
    }

    private String journalIdFor(String txid) {
        return jdbc.sql("select id from ledger.journal_entries where event_id in "
                + "(select event_id from ledger.events where txid = :t and type = 'refund.created' order by received_at desc limit 1)")
                .param("t", txid).query(String.class).optional().orElse(null);
    }

    private void assertProofOk() {
        var proof = reconciliation.proof();
        assertThat(proof.ok())
                .withFailMessage("proof failed: %s", proof.firstDivergence())
                .isTrue();
    }

    @TestConfiguration
    static class RefundTestConfig {

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
                    .cleanDisabled(false)
                    .load();
            flyway.clean();
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
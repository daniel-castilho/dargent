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
import tools.jackson.databind.json.JsonMapper;

/**
 * E8 S6 balance-guard IT (spec §7.3, scenario 19). The {@code MerchantBalancePort} is the
 * composition-root adapter reading the ledger's available balance, so the guard executes here
 * end-to-end over the real HTTP surface.
 * <ul>
 *   <li>Insufficient available balance → 409 {@code insufficient_merchant_balance} with ZERO
 *       writes (no refunds row, no outbox {@code refund.created}, no journal, no postings).</li>
 *   <li>A guard pass leaves the flow intact → 201 + journal posted.</li>
 * </ul>
 * The fail-closed port-down leg (409 {@code balance_unavailable}) is owned by the unit test
 * {@code RefundPaymentUseCaseTest.fails_closed_when_balance_port_down} — it cannot be forced at
 * the HTTP layer without stubbing the database (rule 3.9 forbids stubbing the DB). The insufficient
 * path is what the ledger drain backstop (scenario 19) is designed to catch.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, RefundBalanceGuardIT.RefundGuardTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.ledger.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class RefundBalanceGuardIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
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

    /** Refunding more than the merchant's available balance → 409, zero writes, proof untouched. */
    @Test
    void insufficient_merchant_balance_is_409_with_zero_writes_and_unchanged_payment() throws Exception {
        String txid = txid("ING");

        // Payment says 100.00; the ledger only holds 30.00 available (refund of 40.00 needs 39.60).
        seedConferencePayment(txid, 10000, 100);
        fundLedgerAvailable(3030, 30); // available = 3000 < net drain 3960 → guard fails closed

        var resp = postRefund(txid, "refund-key-in-01", "req-refund-in-01", "{\"amount\":4000}");
        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(resp.body()).path("code").asText())
                .isEqualTo("insufficient_merchant_balance");

        // Zero writes attributable to the refund: no refund row, no refund.created outbox, and the
        // ledger never saw a refund event — the funding confirm's 3 postings are the only ones.
        assertThat(refundCount(txid)).isZero();
        assertThat(outboxRefundCount()).isZero();
        assertThat(refundEventCount()).isZero();
        assertThat(postingsCount()).isEqualTo(3); // funding confirm only
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");
        assertThat(refundedCents(txid)).isZero();
        assertProofOk();
    }

    /** Guard pass lets the flow continue: 201 + journal [3]+[4] posted at the ledger. */
    @Test
    void guard_pass_allows_refund_flow_to_post_journal() throws Exception {
        String txid = txid("GOK");

        seedConferencePayment(txid, 10000, 100);
        fundLedgerAvailable(10000, 100); // ample: available 9900 for a 40.00 refund (net 39.60)

        var resp = postRefund(txid, "refund-key-ok-01", "req-refund-ok-01", "{\"amount\":4000}");
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(MAPPER.readTree(resp.body()).path("status").asText()).isEqualTo("SUCCEEDED");

        // The outbox refund.created was appended by the request path.
        assertThat(outboxRefundCount()).isEqualTo(1);

        // Drive the ledger consumer for the refund event and assert the journal posts.
        String refundEventId = UUID.randomUUID().toString();
        ingestion.processMessage(refundEnvelopeWithEventId(refundEventId, txid, 4000, 40, 3960));
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(5940);
        assertThat(balance("payments:processing")).isEqualTo(-6000);
        assertThat(balance("fees:revenue")).isEqualTo(60);
        assertProofOk();
    }

    // ------------------------------------------------------------------ helpers

    private void fundLedgerAvailable(long amount, long fee) {
        assertThat(ingestion.processMessage(confirmedEnvelope(txid("FND"), amount, fee))).isTrue();
    }

    private String txid(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 22);
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

    private String refundEnvelopeWithEventId(String eventId, String txid, long amount, long feeReversal, long net) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"refund.created"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-refund-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"feeRefund\":" + feeReversal + ",\"netRefund\":" + net
                + ",\"txid\":\"" + txid + "\",\"refundId\":\"" + UUID.randomUUID() + "\"}}";
    }

    private void seedConferencePayment(String txid, long amount, long fee) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-02T10:00:00Z");
        Instant confirmedAt = Instant.parse("2026-09-02T10:05:00Z");
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'refund-it', :amount, 'CONFIRMED', 1,
                    :expiresAt, :e2e, :fee, :net, false, 0, :created, :confirmed, null, 0)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("amount", amount)
                .param("fee", fee)
                .param("net", amount - fee)
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

    private long refundedCents(String txid) {
        return jdbc.sql("select refunded_cents from payments.payments where txid = :t")
                .param("t", txid).query(Long.class).single();
    }

    private long refundCount(String txid) {
        return jdbc.sql("select count(*) from payments.refunds where txid = :t")
                .param("t", txid).query(Long.class).single();
    }

    private long outboxRefundCount() {
        return jdbc.sql("select count(*) from payments.outbox where type = 'refund.created'")
                .query(Long.class).single();
    }

    private long refundEventCount() {
        return jdbc.sql("select count(*) from ledger.events where type = 'refund.created'")
                .query(Long.class).single();
    }

    private long postingsCount() {
        return jdbc.sql("select count(*) from ledger.postings").query(Long.class).single();
    }

    private void assertProofOk() {
        var proof = reconciliation.proof();
        assertThat(proof.ok())
                .withFailMessage("proof failed: %s", proof.firstDivergence())
                .isTrue();
    }

    @TestConfiguration
    static class RefundGuardTestConfig {

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

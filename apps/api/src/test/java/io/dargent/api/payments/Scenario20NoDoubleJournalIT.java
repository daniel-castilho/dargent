package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.shared.events.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * E9 §6.4 Scenario 20 no-double-journaling proof (deterministic harness):
 * A republished {@code payment.confirmed} event consumed twice-equivalently
 * → journal count unchanged, balances unchanged.
 *
 * Deterministic harness (no SNS/SQS): directly invokes {@link EventIngestionUseCase}
 * with the original envelope, then the republished envelope (deterministic UUID v3
 * from {@code originalEventId:r{n}}). The ledger guard {@code hasPostedJournalForTxid}
 * ensures the second ingestion is a no-op on journal/balances, and the event is
 * marked POSTED with the ratified note.
 *
 * Flow:
 * 1. Create original payment.confirmed envelope → ingest → journal = 1
 * 2. Create republished envelope with deterministic UUID v3 from {@code originalEventId:r1}
 *    → ingest → journal count unchanged, balances unchanged, event POSTED with note
 * 3. Re-run same republished envelope → dedupe by eventId → journal/balances unchanged
 *
 * Zero async, zero sleeps. Clock injected.
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
    EventIngestionUseCase ledgerIngestion;

    @Autowired
    io.dargent.ledger.domain.port.out.LedgerStore ledgerStore;

    @Autowired
    io.dargent.payments.application.OutboxDeliveryUseCase relay;

    @Autowired
    io.dargent.payments.application.OutboxDeliveryUseCase.Policy relayPolicy;

    @org.springframework.test.context.DynamicPropertySource
    static void env(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SNS).toString());
        registry.add("AWS_REGION", () -> "us-east-1");
        registry.add("AWS_ACCESS_KEY_ID", () -> "test");
        registry.add("AWS_SECRET_ACCESS_KEY", () -> "test");
        registry.add("DARGENT_EVENTS_TOPIC_ARN", () -> "arn:aws:sns:us-east-1:000000000000:unused");
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
        jdbc.sql("truncate payments.outbox, payments.audit_log, payments.api_keys, "
                + "ledger.events, ledger.journal_entries, ledger.postings, ledger.balances, ledger.audit_log "
                + "restart identity cascade").update();
        insertKey(ADMIN_KEY_ID, ADMIN_RAW_KEY, null);
    }

    @Test
    void republished_payment_confirmed_consumed_twice_equivalently_no_double_journal() throws Exception {
        // 1) Create original payment.confirmed envelope and ingest
        String originalEventId = UUID.randomUUID().toString();
        String txid = txid(UUID.randomUUID());
        String originalEnvelope = envelope("payment.confirmed", originalEventId, txid, 10_000, 500);

        boolean ingested1 = ledgerIngestion.processMessage(originalEnvelope);
        assertThat(ingested1).isTrue();

        long journalCountAfterOriginal = countJournalEntries(txid);
        long balanceBefore = getAvailableBalance();
        assertThat(journalCountAfterOriginal).isEqualTo(1);
        assertThat(balanceBefore).isGreaterThan(0);

        // DEBUG: Check if guard would trigger
        boolean guardBeforeRepublish = ledgerStore.hasPostedJournalForTxid(txid);
        System.out.println("DEBUG: Guard before republish = " + guardBeforeRepublish);

        // 2) Create republished envelope with deterministic UUID v3 from originalEventId:r1
        String republishedEventId = UUID.nameUUIDFromBytes((originalEventId + ":r1").getBytes(StandardCharsets.UTF_8)).toString();
        String republishedEnvelope = envelope("payment.confirmed", republishedEventId, txid, 10_000, 500);

        // 3) Ingest republished event → guard should prevent double journaling
        boolean ingested2 = ledgerIngestion.processMessage(republishedEnvelope);
        assertThat(ingested2).isTrue();

        // DEBUG: Check guard after republish
        boolean guardAfterRepublish = ledgerStore.hasPostedJournalForTxid(txid);
        System.out.println("DEBUG: Guard after republish = " + guardAfterRepublish);

        // 4) Assert no double journaling: journal count unchanged, balances unchanged
        long journalCountAfterRepublish = countJournalEntries(txid);
        long balanceAfter = getAvailableBalance();

        assertThat(journalCountAfterRepublish).as("Journal count must not increase for republished event")
                .isEqualTo(1);
        assertThat(balanceAfter).as("Balances must not change for republished event")
                .isEqualTo(balanceBefore);

        // 4) Re-run same republished envelope → dedupe by eventId → journal/balances unchanged
        boolean ingested3 = ledgerIngestion.processMessage(republishedEnvelope);
        assertThat(ingested3).isTrue();

        long journalCountAfterRerun = countJournalEntries(txid);
        long balanceAfterRerun = getAvailableBalance();

        assertThat(journalCountAfterRerun).as("Journal count must not increase on re-run")
                .isEqualTo(1);
        assertThat(balanceAfterRerun).as("Balances must not change on re-run")
                .isEqualTo(balanceBefore);

        // Verify event status is POSTED with ratified note
        UUID republishedEventIdUUID = UUID.fromString(republishedEventId);
        String eventStatus = jdbc.sql("select status from ledger.events where event_id = ?")
                .param(republishedEventIdUUID).query(String.class).optional().orElse("MISSING");
        String eventNote = jdbc.sql("select note from ledger.events where event_id = ?")
                .param(republishedEventIdUUID).query(String.class).optional().orElse("MISSING");
        assertThat(eventStatus).isEqualTo("POSTED");
        assertThat(eventNote).contains("already journaled");
    }

    // ------------------------------------------------------------- helpers

    private String envelope(String type, String eventId, String txid, long amount, long fee) {
        try {
            long net = amount - fee;
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("txid", txid);
            payload.put("merchantId", MERCHANT.toString());
            payload.put("amount", amount);
            payload.put("fee", fee);
            payload.put("net", net);
            payload.put("late", false);
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("eventId", eventId);
            envelope.put("type", type);
            envelope.put("version", 1);
            envelope.put("aggregateId", txid);
            envelope.put("merchantId", MERCHANT.toString());
            envelope.put("requestId", "req-scn20-" + UUID.randomUUID());
            envelope.put("occurredAt", "2027-01-03T10:00:00Z");
            envelope.put("payload", payload);
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
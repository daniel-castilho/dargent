package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E13 S4 R2 — readiness health group over the management port, GOOD targets. Spine ON (relay +
 * both consumers) so the shared SqsClient/SnsClient beans exist and the SNS/SQS indicators
 * register into the {@code readiness} group. Every probe target resolves → readiness = UP.
 * Liveness untouched (and asserted) — the split works.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = DargentApiApplication.class,
        properties = {
            "management.server.port=9090",
            "dargent.relay.enabled=true",
            "dargent.ledger.consumer.enabled=true",
            "dargent.notifs.consumer.enabled=true",
            "dargent.psp.webhook-secret=dev-only-secret",
            "DARGENT_RELAY_BATCH=10",
            "DARGENT_RELAY_WORKERS=1",
            "DARGENT_RELAY_POLL_MS=3600000",
            "DARGENT_RELAY_MAX_ATTEMPTS=3",
            "DARGENT_OUTBOX_RETENTION_DAYS=7",
            "DARGENT_EVENTS_PUBLISH_TIMEOUT_MS=2000",
            "DARGENT_LEDGER_BATCH=10",
            "DARGENT_LEDGER_POLL_MS=3600000",
            "DARGENT_NOTIFS_BATCH=10",
            "DARGENT_NOTIFS_POLL_MS=3600000"
        })
@Testcontainers
class ReadinessHealthGoodIT extends ReadinessHealthSupport {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final int MGMT_PORT = 9090;

    @BeforeAll
    static void boot() {
        ensureTopology();
    }

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry registry) {
        registry.add("AWS_ENDPOINT_URL", ReadinessHealthSupport::endpointOverride);
        registry.add("AWS_REGION", () -> REGION);
        registry.add("DARGENT_EVENTS_TOPIC_ARN", ReadinessHealthSupport::goodTopicArn);
        registry.add("DARGENT_LEDGER_QUEUE_URL", ReadinessHealthSupport::goodLedgerUrl);
        registry.add("DARGENT_NOTIFS_QUEUE_URL", ReadinessHealthSupport::goodNotifsUrl);
    }

    @Test
    void readiness_and_liveness_are_up_when_sns_and_queues_resolve() throws Exception {
        boolean ok = false;
        String readiness = "";
        for (int i = 0; i < 20 && !ok; i++) {
            readiness = get(MGMT_PORT, "/actuator/health/readiness");
            ok = readiness.contains("\"status\":\"UP\"");
            if (!ok) {
                Thread.sleep(500);
            }
        }
        assertThat(readiness).as("readiness group").contains("\"status\":\"UP\"");
        String liveness = get(MGMT_PORT, "/actuator/health/liveness");
        assertThat(liveness).as("liveness group").contains("\"status\":\"UP\"");
    }
}

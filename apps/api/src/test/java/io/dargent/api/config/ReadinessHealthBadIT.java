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
 * E13 S4 R2 — readiness health group over the management port, BAD target. Same spine as
 * {@link ReadinessHealthGoodIT} but {@code DARGENT_LEDGER_QUEUE_URL} is overridden to a URL
 * that does not exist (deliberately-bad endpoint — spec contract). The SQS indicator falls →
 * readiness = DOWN while liveness stays UP (process alive): the split's whole point, and the
 * blue-green deploy gate that consumes readiness stops the baking color.
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
class ReadinessHealthBadIT extends ReadinessHealthSupport {

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
        registry.add("DARGENT_NOTIFS_QUEUE_URL", ReadinessHealthSupport::goodNotifsUrl);
        // Deliberately-bad ledger queue URL — no such queue in LocalStack.
        registry.add("DARGENT_LEDGER_QUEUE_URL", () -> "http://localhost:0/nonexistent-ledger-queue");
    }

    @Test
    void readiness_down_but_liveness_up_when_ledger_queue_unreachable() throws Exception {
        String readiness = "";
        boolean down = false;
        for (int i = 0; i < 20 && !down; i++) {
            readiness = get(MGMT_PORT, "/actuator/health/readiness");
            down = readiness.contains("\"status\":\"DOWN\"");
            if (!down) {
                Thread.sleep(500);
            }
        }
        assertThat(readiness).as("readiness group").contains("\"status\":\"DOWN\"");
        String liveness = get(MGMT_PORT, "/actuator/health/liveness");
        assertThat(liveness).as("liveness group").contains("\"status\":\"UP\"");
    }
}

package io.dargent.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.ledger.adapter.out.messaging.SqsEventConsumer;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * IT6 (E7 §7): poison → DLQ. A valid confirmed envelope and a structurally unparsable body are both
 * published to the ledger FIFO queue. {@code SqsEventConsumer.runOnce()} acks the valid one (journal
 * posted, proof ok) and leaves the poison un-acked; after the receive count exceeds maxReceiveCount
 * the poison is received from the DLQ. Zero Java sleeps — all waiting is absorbed by SQS long-polls
 * (the E6 S6 lesson).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, LedgerPoisonDlqIT.PoisonTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.ledger.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class LedgerPoisonDlqIT {

    private static final String REGION = "us-east-1";
    private static final String LEDGER_QUEUE = "dargent-payments-ledger-dlqit.fifo";
    private static final String LEDGER_DLQ = "dargent-payments-ledger-dlq-dlqit.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final String POISON_BODY = "{\"hello\":\"world\"}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    private static SqsClient sqs;
    private static String ledgerUrl;
    private static String dlqUrl;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SqsEventConsumer ledgerConsumer;

    @Autowired
    LedgerReconciliationUseCase reconciliation;

    @org.springframework.test.context.DynamicPropertySource
    static void awsEnvironment(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ensureTopology();
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SQS).toString());
        registry.add("AWS_REGION", () -> REGION);
        registry.add("AWS_ACCESS_KEY_ID", () -> "test");
        registry.add("AWS_SECRET_ACCESS_KEY", () -> "test");
        registry.add("DARGENT_LEDGER_QUEUE_URL", () -> ledgerUrl);
    }

    /** The poison is nacked (never deletes), the valid envelope is acked; poison redrives to DLQ. */
    @Test
    void poison_message_is_not_acked_and_redrives_to_dlq_while_valid_message_posts() {
        publish(LEDGER_QUEUE, "poison-gid", "poison-dedup-1", POISON_BODY);
        publish(LEDGER_QUEUE, "dlqit-valid-tx", "dlqit-dedup-valid", confirmedEnvelope("dlqit-valid-tx"));

        // Real consumer path: acks the valid envelope (posted), leaves the poison un-acked.
        assertThat(ledgerConsumer.runOnce()).isGreaterThan(0);

        // Valid message posted exactly once; the poison never posts anything and proof stays ok.
        assertThat(journalEntries()).isEqualTo(1);
        assertThat(postings()).isEqualTo(3);
        assertProofOk();

        // The poison redrives to the DLQ once its receive count exceeds maxReceiveCount (2).
        String dlqMessage = waitForPoisonInDlq();
        assertThat(dlqMessage).as("poison should have reached the DLQ").contains("hello");

        // App still healthy: a rebuild + proof on the valid data stays green.
        assertProofOk();
    }

    // ------------------------------------------------------------------ helpers

    private String waitForPoisonInDlq() {
        // Zero Java sleep: every barrier is an SQS long-poll that absorbs the visibility window and
        // the redrive delay. Bounded so a redrive regression fails fast instead of hanging.
        for (int i = 0; i < 60; i++) {
            bumpPoisonReceiveCount();
            List<Message> dlq = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(dlqUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .build()).messages();
            if (!dlq.isEmpty()) {
                return dlq.get(0).body();
            }
        }
        return null;
    }

    private void bumpPoisonReceiveCount() {
        sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(ledgerUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .build());
    }

    private void publish(String queueUrl, String groupId, String dedupeId, String body) {
        sqs.sendMessage(r -> r.queueUrl(queueUrl)
                .messageGroupId(groupId)
                .messageDeduplicationId(dedupeId)
                .messageBody(body));
    }

    private String confirmedEnvelope(String txid) {
        long amount = 7000;
        long fee = 100;
        long net = amount - fee;
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"payment.confirmed"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"fee\":" + fee + ",\"net\":" + net
                + ",\"late\":false,\"txid\":\"" + txid + "\"}}";
    }

    private long journalEntries() {
        return jdbc.sql("select count(*) from ledger.journal_entries").query(Long.class).single();
    }

    private long postings() {
        return jdbc.sql("select count(*) from ledger.postings").query(Long.class).single();
    }

    private void assertProofOk() {
        var proof = reconciliation.proof();
        assertThat(proof.ok())
                .withFailMessage("proof failed: %s", proof.firstDivergence())
                .isTrue();
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
        dlqUrl = createFifoQueue(sqs, LEDGER_DLQ, null);
        String dlqArn = sqs.getQueueAttributes(r -> r.queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}";
        ledgerUrl = createFifoQueue(sqs, LEDGER_QUEUE, redrive);
        // Short visibility so the poison becomes visible again quickly for its redrive journey.
        sqs.setQueueAttributes(r -> r.queueUrl(ledgerUrl)
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1")));
    }

    private static String createFifoQueue(SqsClient client, String name, String redrive) {
        Map<QueueAttributeName, String> attrs = new LinkedHashMap<>();
        attrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        if (redrive != null) {
            attrs.put(QueueAttributeName.REDRIVE_POLICY, redrive);
        }
        return client.createQueue(r -> r.queueName(name).attributes(attrs)).queueUrl();
    }

    @TestConfiguration
    static class PoisonTestConfig {

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
        @Primary
        SqsClient poisonTestSqsClient() {
            return sqs;
        }

        @Bean
        SqsEventConsumer poisonConsumer(EventIngestionUseCase ingestion,
                SqsClient poisonTestSqsClient) {
            return new SqsEventConsumer(poisonTestSqsClient, ledgerUrl, 10, 600000, ingestion);
        }
    }
}
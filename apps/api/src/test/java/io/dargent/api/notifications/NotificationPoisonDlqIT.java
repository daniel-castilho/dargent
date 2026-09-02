package io.dargent.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.notifications.adapter.out.messaging.SqsNotificationConsumer;
import io.dargent.notifications.application.NotificationIngestionUseCase;
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
 * S5 — E10 spec §8.2 NotificationPoisonDlqIT.
 * A structurally unparsable body is published to the notifications FIFO queue. The consumer acks
 * nothing for it (ingestion returns false → nack path); after the receive count exceeds
 * maxReceiveCount the poison lands in the notify DLQ. Mirrors the ledger poison IT mechanics
 * (LedgerPoisonDlqIT): zero Java sleeps, all barriers absorbed by SQS long-polls.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, NotificationPoisonDlqIT.PoisonTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.notifs.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class NotificationPoisonDlqIT {

    private static final String REGION = "us-east-1";
    private static final String NOTIFS_QUEUE = "dargent-payments-notif-dlqit.fifo";
    private static final String NOTIFS_DLQ = "dargent-payments-notif-dlq-dlqit.fifo";
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
    private static String notifsUrl;
    private static String dlqUrl;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SqsNotificationConsumer notifsConsumer;

    @org.springframework.test.context.DynamicPropertySource
    static void awsEnvironment(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ensureTopology();
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SNS).toString());
        registry.add("AWS_REGION", () -> REGION);
        registry.add("AWS_ACCESS_KEY_ID", () -> "test");
        registry.add("AWS_SECRET_ACCESS_KEY", () -> "test");
        registry.add("DARGENT_NOTIFS_QUEUE_URL", () -> notifsUrl);
    }

    /** The poison is nacked (never deletes) and redrives to the notify DLQ; no row is written. */
    @Test
    void poison_message_is_not_acked_and_redrives_to_notify_dlq_with_no_row() {
        publish(NOTIFS_QUEUE, "poison-gid", "poison-dedup-1", POISON_BODY);

        // Real consumer path: runOnce processes the poison; ingestion returns false → nack.
        assertThat(notifsConsumer.runOnce()).isZero();

        // No notification row is ever written for the poison.
        assertThat(notificationRows()).isZero();

        // The poison redrives to the DLQ once its receive count exceeds maxReceiveCount (2).
        String dlqMessage = waitForPoisonInDlq();
        assertThat(dlqMessage).as("poison should have reached the notify DLQ").contains("hello");

        // Still no row after redrive — the poison never writes state.
        assertThat(notificationRows()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String waitForPoisonInDlq() {
        // Zero Java sleep: every barrier is an SQS long-poll that absorbs the visibility window
        // and the redrive delay. Bounded so a redrive regression fails fast instead of hanging.
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
                .queueUrl(notifsUrl)
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

    private long notificationRows() {
        return jdbc.sql("select count(*) from notifications.notification").query(Long.class).single();
    }

    private static synchronized void ensureTopology() {
        if (notifsUrl != null) {
            return;
        }
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
        dlqUrl = createFifoQueue(sqs, NOTIFS_DLQ, null);
        String dlqArn = sqs.getQueueAttributes(r -> r.queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}";
        notifsUrl = createFifoQueue(sqs, NOTIFS_QUEUE, redrive);
        // Short visibility so the poison becomes visible again quickly for its redrive journey.
        sqs.setQueueAttributes(r -> r.queueUrl(notifsUrl)
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
                    .schemas("payments", "ledger", "notifications")
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
        SqsNotificationConsumer poisonConsumer(NotificationIngestionUseCase ingestion,
                SqsClient poisonTestSqsClient) {
            return new SqsNotificationConsumer(poisonTestSqsClient, notifsUrl, 10, 1000, ingestion);
        }
    }
}

package io.dargent.api.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * E13 S4 R2 — shared topology + HTTP helpers for the readiness health ITs. NOT a test class
 * (no {@code @Test}): Failsafe only runs the concrete {@code ReadinessHealth*IT} classes.
 * Purpose: one LocalStack (SNS+SQS) + PG16, the real "existing client" targets (queues + topic),
 * and the operand for the deliberate-bad-URL override in the DOWN context.
 */
@SuppressWarnings("unused")
public abstract class ReadinessHealthSupport {

    static final String REGION = "us-east-1";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    private static SqsClient sqs;
    private static SnsClient sns;
    private static String ledgerUrl;
    private static String notifsUrl;
    private static String topicArn;

    /** Creates the queues + topic. Safe to call from each context; idempotent per JVM. */
    static synchronized void ensureTopology() {
        if (ledgerUrl != null) {
            return;
        }
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        sns = SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        ledgerUrl = createFifoQueue(sqs, "dargent-readiness-ledger.fifo");
        notifsUrl = createFifoQueue(sqs, "dargent-readiness-notifs.fifo");
        topicArn = sns.createTopic(r -> r.name("dargent-readiness-topic.fifo")
                        .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false")))
                .topicArn();
    }

    private static String createFifoQueue(SqsClient client, String name) {
        Map<QueueAttributeName, String> attrs = new LinkedHashMap<>();
        attrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        return client.createQueue(r -> r.queueName(name).attributes(attrs)).queueUrl();
    }

    static String endpointOverride() {
        return localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString();
    }

    static String goodLedgerUrl() {
        return ledgerUrl;
    }

    static String goodNotifsUrl() {
        return notifsUrl;
    }

    static String goodTopicArn() {
        return topicArn;
    }

    static String get(int port, String path) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + path))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() + " " + resp.body();
    }
}

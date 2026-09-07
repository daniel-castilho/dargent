package io.dargent.api.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * E13 S4 R2 — shared topology + HTTP helpers for the readiness health ITs. NOT a test class
 * (no {@code @Test}, no containers — those live on the concrete ITs, house style): Failsafe
 * runs only the concrete {@code ReadinessHealth*IT} classes. One LocalStack (SNS+SQS) supplies
 * the real "existing client" targets (queues + topic); the DOWN context overrides one URL.
 */
public abstract class ReadinessHealthSupport {

    static final String REGION = "us-east-1";

    static final LocalStackContainer localstack = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    private static SqsClient sqs;
    private static String ledgerUrl;
    private static String notifsUrl;
    private static String topicArn;

    /**
     * Starts LocalStack once per JVM and creates the queues + topic. NOT a JUnit-managed
     * {@code @Container} on purpose: a shared static container is stopped by the extension
     * after the FIRST IT class while the second class still references its URLs (the CI
     * cross-class failure — readiness DOWN in the GOOD context). Manual lifecycle; Ryuk reaps
     * at JVM exit; both IT classes in the same fork share one LocalStack.
     */
    static synchronized void ensureTopology() {
        if (!localstack.isRunning()) {
            localstack.start();
        }
        if (ledgerUrl != null) {
            return;
        }
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        SnsClient sns = SnsClient.builder()
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

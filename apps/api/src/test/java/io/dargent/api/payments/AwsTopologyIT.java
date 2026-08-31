package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IT6 (E6 §7, §5.2): the provisioned delivery topology carries the attributes the design depends on —
 * {@code FifoQueue=true} on the notify queue AND its DLQ, {@code RedrivePolicy maxReceiveCount=5},
 * and a live topic→queue subscription. The IT provisions the topology exactly like
 * {@code deploy/localstack-init.sh} and reads the attributes back via {@code GetQueueAttributes} /
 * SNS topic+subscription APIs — asserted, never assumed. No Spring: pure AWS SDK against LocalStack.
 */
@Testcontainers
class AwsTopologyIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_NAME = "dargent-payments-events-topo.fifo";
    private static final String QUEUE_NAME = "dargent-payments-notify-topo.fifo";
    private static final String DLQ_NAME = "dargent-payments-notify-dlq-topo.fifo";
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    private static SnsClient sns;
    private static SqsClient sqs;
    private static String topicArn;
    private static String notifyUrl;
    private static String notifyArn;
    private static String dlqUrl;
    private static String dlqArn;

    @BeforeAll
    static void provisionTopology() {
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(REGION))
                .build();
        sns = SnsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
                .region(Region.of(REGION))
                .build();

        dlqUrl = sqs.createQueue(r -> r.queueName(DLQ_NAME)
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
        dlqArn = queueArn(dlqUrl);

        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
        notifyUrl = sqs.createQueue(r -> r.queueName(QUEUE_NAME)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.REDRIVE_POLICY, redrive))).queueUrl();
        notifyArn = queueArn(notifyUrl);

        topicArn = sns.createTopic(r -> r.name(TOPIC_NAME)
                .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false"))).topicArn();
        sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(notifyArn));
    }

    @Test
    void notify_queue_is_fifo_with_redrive_max_receive_count_5_and_subscription_exists() {
        // Notify queue: FIFO + redrive to the DLQ with maxReceiveCount = 5
        Map<QueueAttributeName, String> attrs = getQueueAttributes(notifyUrl);
        assertThat(attrs).containsEntry(QueueAttributeName.FIFO_QUEUE, "true");
        JsonNode redrive = MAPPER.readTree(attrs.get(QueueAttributeName.REDRIVE_POLICY));
        assertThat(redrive.path("deadLetterTargetArn").asText()).isEqualTo(dlqArn);
        assertThat(redrive.path("maxReceiveCount").asText()).isEqualTo("5");

        // DLQ: FIFO too (per-payment ordering survives retention/redrive)
        Map<QueueAttributeName, String> dlqAttrs = getQueueAttributes(dlqUrl);
        assertThat(dlqAttrs).containsEntry(QueueAttributeName.FIFO_QUEUE, "true");

        // Topic: FIFO, content-based dedup off (relay always sends MessageDeduplicationId — §5.2)
        var topicAttrs = sns.getTopicAttributes(r -> r.topicArn(topicArn)).attributes();
        assertThat(topicAttrs).containsEntry("FifoTopic", "true");
        assertThat(topicAttrs).containsEntry("ContentBasedDeduplication", "false");

        // Subscription: the notify queue is subscribed to the events topic
        var subs = sns.listSubscriptionsByTopic(r -> r.topicArn(topicArn)).subscriptions();
        assertThat(subs).anyMatch(s -> "sqs".equals(s.protocol()) && notifyArn.equals(s.endpoint()));
    }

    private static String queueArn(String url) {
        return getQueueAttributes(url).get(QueueAttributeName.QUEUE_ARN);
    }

    private static Map<QueueAttributeName, String> getQueueAttributes(String url) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(url)
                .attributeNames(QueueAttributeName.ALL)
                .build()).attributes();
    }
}
package io.dargent.payments.adapter.out.messaging;

import io.dargent.payments.domain.port.out.EventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import java.time.Duration;

/**
 * SNS event publisher (E6 §5.2): publishes outbox events to SNS FIFO topic.
 * <p>
 * Message attributes:
 * <ul>
 *   <li>MessageGroupId = aggregateId (per-payment FIFO ordering)</li>
 *   <li>MessageDeduplicationId = eventId (5-min FIFO content dedup)</li>
 *   <li>Subject = event type</li>
 *   <li>Body = stored payload verbatim (jsonb text)</li>
 * </ul>
 * AWS SDK v2 url-connection client; per-call timeout via SDK override.
 */
@Component
public class SnsEventPublisher implements EventPublisher {

    private final SnsClient sns;
    private final String topicArn;
    private final Duration timeout;

    public SnsEventPublisher(
            @Value("${DARGENT_EVENTS_TOPIC_ARN}") String topicArn,
            @Value("${DARGENT_EVENTS_PUBLISH_TIMEOUT_MS}") long timeoutMs,
            @Value("${AWS_REGION}") String region,
            @Value("${AWS_ENDPOINT_URL}") String endpointUrl
    ) {
        this.topicArn = topicArn;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.sns = SnsClient.builder()
                .region(Region.of(region))
                .endpointOverride(java.net.URI.create(endpointUrl))
                .build();
    }

    @Override
    public void publish(String type, String payload, String eventId, String aggregateId) {
        PublishRequest request = PublishRequest.builder()
                .topicArn(topicArn)
                .message(payload)
                .messageGroupId(aggregateId)
                .messageDeduplicationId(eventId)
                .subject(type)
                .build();
        sns.publish(request);
    }
}
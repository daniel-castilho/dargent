package io.dargent.payments.adapter.out.messaging;

import io.dargent.payments.domain.port.out.EventPublisher;
import java.time.Duration;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * SNS event publisher (E6 §5.2): publishes outbox events to SNS FIFO topic.
 * <p>
 * Delivery guarantee (E6 §5.6): <b>at-least-once</b>, per-payment FIFO ordering,
 * dedup by {@code MessageDeduplicationId = eventId} (5-min FIFO window), consumer
 * idempotency by {@code eventId} is E10's binding contract. Never "exactly once".
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
            @Value("${AWS_ENDPOINT_URL}") String endpointUrl,
            @Value("${AWS_ACCESS_KEY_ID:test}") String accessKey,
            @Value("${AWS_SECRET_ACCESS_KEY:test}") String secretKey
    ) {
        this.topicArn = topicArn;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.sns = SnsClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointUrl))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        // Per-call timeout override (E6 §4.1): bounds SNS SDK internal
                        // retry amplification during an outage (E6 §8 risk table).
                        .apiCallAttemptTimeout(timeout)
                        .apiCallTimeout(timeout)
                        .build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
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
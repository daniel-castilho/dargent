package io.dargent.payments.adapter.out.messaging;

import io.dargent.payments.domain.port.out.EventPublisher;
import java.net.URI;
import java.time.Duration;
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

    /**
     * Primary constructor: consumes the relay's shared {@code SnsClient} bean (E13 R2 readiness
     * probes the same client) — one client per app, never a probe-only second client.
     */
    public SnsEventPublisher(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    /**
     * Self-building constructor — keeps module ITs ({@code OutboxRelayIT},
     * {@code OutboxExhaustionIT}) and old wiring intact with the E6 per-call timeout contract.
     */
    public SnsEventPublisher(
            String topicArn, long timeoutMs, String region, String endpointUrl, String accessKey, String secretKey) {
        this(buildClient(timeoutMs, region, endpointUrl, accessKey, secretKey), topicArn);
    }

    private static SnsClient buildClient(
            long timeoutMs, String region, String endpointUrl, String accessKey, String secretKey) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        return SnsClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointUrl))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        // Per-call timeout override (E6 §4.1): bounds SNS SDK internal
                        // retry amplification during an outage (E6 §8 risk table).
                        .apiCallAttemptTimeout(timeout)
                        .apiCallTimeout(timeout)
                        .build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
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

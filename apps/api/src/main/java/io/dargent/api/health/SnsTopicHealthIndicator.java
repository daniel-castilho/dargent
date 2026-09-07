package io.dargent.api.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest;

/**
 * E13 S4 R2: SNS readiness probe. Calls {@code get-topic-attributes} on
 * {@code DARGENT_EVENTS_TOPIC_ARN} through the relay's shared {@link SnsClient} (LocalStack-compatible).
 * DOWN on any failure — the readiness group falls, and the blue-green deploy gate that consumes
 * {@code /actuator/health/readiness} stops routing new traffic to the baking color.
 */
public final class SnsTopicHealthIndicator implements HealthIndicator {

    private final SnsClient sns;
    private final String topicArn;

    public SnsTopicHealthIndicator(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Override
    public Health health() {
        try {
            sns.getTopicAttributes(
                    GetTopicAttributesRequest.builder().topicArn(topicArn).build());
            return Health.up().withDetail("topic", topicArn).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("topic", topicArn).build();
        }
    }
}

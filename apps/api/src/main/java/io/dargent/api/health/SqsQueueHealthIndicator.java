package io.dargent.api.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;

/**
 * E13 S4 R2: SQS readiness probe. Calls {@code get-queue-attributes} on the given queue URL
 * through an existing {@link SqsClient} (ledger or notifications consumer — LocalStack-compatible).
 * DOWN on any failure so the {@code readiness} group falls when a consumer queue is unreachable.
 */
public final class SqsQueueHealthIndicator implements HealthIndicator {

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsQueueHealthIndicator(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public Health health() {
        try {
            sqs.getQueueAttributes(
                    GetQueueAttributesRequest.builder().queueUrl(queueUrl).build());
            return Health.up().withDetail("queue", queueUrl).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("queue", queueUrl).build();
        }
    }
}

package io.dargent.api.config;

import io.dargent.api.health.SnsTopicHealthIndicator;
import io.dargent.api.health.SqsQueueHealthIndicator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * E13 S4 R2: readiness health indicators — one per spine dependency (SNS topic + the two SQS
 * consumer queues), each consuming an EXISTING shared client (no probe-only clients). Each
 * indicator mirrors the exact {@code @ConditionalOnProperty} of its client bean, so the two are
 * always in phase (no config-class ordering trap): indicator present ⇔ client present. The
 * {@code readiness} group therefore reflects precisely the provisioned spine. Liveness untouched.
 * With the default compose (spine off) no indicators exist and the group carries only the db/ping
 * Boot defaults, so the blue-green deploy gate keeps existing behaviour; when the spine is on,
 * a failing SNS/SQS probe falls in group and the gate (E12, consumers of
 * {@code /actuator/health/readiness}) stops the baking color.
 */
@Configuration
public class HealthIndicatorCompositionConfig {

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
    HealthIndicator snsTopicReadiness(
            @Qualifier("snsClient") SnsClient sns, @Value("${DARGENT_EVENTS_TOPIC_ARN}") String topicArn) {
        return new SnsTopicHealthIndicator(sns, topicArn);
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.ledger.consumer.enabled", havingValue = "true", matchIfMissing = false)
    HealthIndicator ledgerQueueReadiness(
            @Qualifier("ledgerSqsClient") SqsClient sqs, @Value("${DARGENT_LEDGER_QUEUE_URL}") String queueUrl) {
        return new SqsQueueHealthIndicator(sqs, queueUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.notifs.consumer.enabled", havingValue = "true", matchIfMissing = false)
    HealthIndicator notifsQueueReadiness(
            @Qualifier("notifsSqsClient") SqsClient sqs, @Value("${DARGENT_NOTIFS_QUEUE_URL}") String queueUrl) {
        return new SqsQueueHealthIndicator(sqs, queueUrl);
    }
}

package io.dargent.api.config;

import io.dargent.notifications.adapter.out.messaging.SqsNotificationConsumer;
import io.dargent.notifications.application.NotificationIngestionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Duration;

/**
 * Notifications composition root — wires the SQS consumer and optional scheduler (E10 spec §4, §6).
 * Hosted in apps/api (boot app convention); notifications module owns logic only.
 */
@Configuration
@ConditionalOnProperty(name = "dargent.notifs.consumer.enabled", havingValue = "true", matchIfMissing = false)
public class NotificationCompositionConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationCompositionConfig.class);

    @Bean
    SqsClient notifsSqsClient(
            @Value("${AWS_ENDPOINT_URL}") String endpointUrl,
            @Value("${AWS_REGION}") String region,
            @Value("${AWS_ACCESS_KEY_ID:test}") String accessKey,
            @Value("${AWS_SECRET_ACCESS_KEY:test}") String secretKey) {
        return SqsClient.builder()
                .endpointOverride(URI.create(endpointUrl))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    @Bean
    SqsNotificationConsumer sqsNotificationConsumer(
            @Qualifier("notifsTestSqsClient") SqsClient sqsClient,
            @Value("${DARGENT_NOTIFS_QUEUE_URL}") String queueUrl,
            @Value("${DARGENT_NOTIFS_BATCH:10}") int batchSize,
            @Value("${DARGENT_NOTIFS_POLL_MS:1000}") long pollMs,
            io.dargent.notifications.application.NotificationIngestionUseCase ingestion) {
        return new SqsNotificationConsumer(
                sqsClient,
                queueUrl,
                Math.min(batchSize, 10), // SQS max batch = 10
                pollMs,
                ingestion
        );
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.notifs.consumer.enabled", havingValue = "true", matchIfMissing = false)
    TaskScheduler notifsScheduler(SqsNotificationConsumer consumer) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notifs-consumer-");
        scheduler.initialize();

        scheduler.scheduleWithFixedDelay(new NotificationsConsumerLifecycle(consumer)::runOnce, Duration.ofSeconds(1));
        return scheduler;
    }
}
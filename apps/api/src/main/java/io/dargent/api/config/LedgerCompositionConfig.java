package io.dargent.api.config;

import io.dargent.ledger.adapter.out.messaging.SqsEventConsumer;
import io.dargent.ledger.application.EventIngestionUseCase;
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
 * Ledger composition root — wires the SQS consumer and optional scheduler (spec §4, §5.1).
 * Hosted in apps/api (boot app convention); ledger module owns logic only.
 */
@Configuration
@ConditionalOnProperty(name = "dargent.ledger.consumer.enabled", havingValue = "true", matchIfMissing = false)
public class LedgerCompositionConfig {

    private static final Logger log = LoggerFactory.getLogger(LedgerCompositionConfig.class);

    @Bean
    SqsClient ledgerSqsClient(
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
    SqsEventConsumer sqsEventConsumer(
            @Qualifier("ledgerSqsClient") SqsClient sqsClient,
            @Value("${DARGENT_LEDGER_QUEUE_URL}") String queueUrl,
            @Value("${DARGENT_LEDGER_BATCH:10}") int batchSize,
            @Value("${DARGENT_LEDGER_POLL_MS:1000}") long pollMs,
            io.dargent.ledger.application.EventIngestionUseCase ingestion) {
        return new SqsEventConsumer(
                sqsClient,
                queueUrl,
                Math.min(batchSize, 10),
                pollMs,
                ingestion
        );
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.ledger.consumer.enabled", havingValue = "true", matchIfMissing = false)
    TaskScheduler ledgerScheduler(SqsEventConsumer consumer) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ledger-consumer-");
        scheduler.initialize();

        scheduler.scheduleWithFixedDelay(new LedgerConsumerLifecycle(consumer)::runOnce, Duration.ofSeconds(1));
        return scheduler;
    }
}
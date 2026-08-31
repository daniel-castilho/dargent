package io.dargent.api.config;

import io.dargent.api.controller.WebhookController;
import io.dargent.api.error.ErrorResponseWriter;
import io.dargent.api.security.ApiKeyAuthenticationFilter;
import io.dargent.api.security.ApiKeyRepository;
import io.dargent.payments.adapter.out.messaging.SnsEventPublisher;
import io.dargent.payments.adapter.out.persistence.JdbcAuditWriter;
import io.dargent.payments.adapter.out.persistence.JdbcIdempotencyStore;
import io.dargent.payments.adapter.out.persistence.JdbcOutboxWriter;
import io.dargent.payments.adapter.out.persistence.JdbcPaymentQueryPort;
import io.dargent.payments.adapter.out.persistence.JdbcWebhookEventStore;
import io.dargent.payments.adapter.out.persistence.JdbcOutboxEventStore;
import io.dargent.payments.adapter.out.persistence.PaymentJpaAdapter;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter;
import io.dargent.payments.application.CreatePaymentUseCase;
import io.dargent.payments.application.EventSerializer;
import io.dargent.payments.application.OutboxDeliveryUseCase;
import io.dargent.payments.application.WebhookIntakeUseCase;
import io.dargent.payments.domain.model.WebhookSignatureValidator;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.EventPublisher;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentQueryPort;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.SecureRandomTxidGenerator;
import io.dargent.payments.domain.port.out.TxidGenerator;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import io.dargent.payments.domain.port.out.WebhookEventStore;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Composition root for the create-payment + webhook paths (E3R MS-2, E4 MS-3).
 * AGENTS.md §2 confines domain logic to the modules and wiring to {@code apps/api};
 * the payments {@code @Repository}/{@code @Component} adapters live in {@code io.dargent.payments.**}
 * and are outside the api app's default {@code io.dargent.api} component scan, so they are declared here explicitly.
 * Every bean is the module adapter or use case — no business rules live in this class.
 * Config values read the E3/E4 spec env contracts; defaults match dev.
 */
@Configuration
public class PaymentsCompositionConfig {

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyRepository repository,
            ErrorResponseWriter errorWriter) {
        return new ApiKeyAuthenticationFilter(repository, errorWriter);
    }

    @Bean
    PaymentJpaAdapter paymentJpaAdapter() {
        return new PaymentJpaAdapter();
    }

    @Bean
    PaymentRepository paymentRepository(PaymentJpaAdapter paymentJpaAdapter) {
        return paymentJpaAdapter;
    }

    @Bean
    PaymentQueryPort paymentQueryPort(JdbcClient jdbc) {
        return new JdbcPaymentQueryPort(jdbc);
    }

    @Bean
    IdempotencyStore idempotencyStore(JdbcClient jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc) {
        return new JdbcOutboxWriter(jdbc);
    }

    @Bean
    AuditWriter auditWriter(JdbcClient jdbc) {
        return new JdbcAuditWriter(jdbc);
    }

    @Bean
    TxidGenerator txidGenerator() {
        return new SecureRandomTxidGenerator();
    }

    @Bean
    EventSerializer eventSerializer() {
        return new EventSerializer();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new tools.jackson.databind.json.JsonMapper();
    }

    // --- webhook beans (E4 MS-3) ---

    @Bean
    WebhookSignatureValidator webhookSignatureValidator(Clock clock) {
        return new WebhookSignatureValidator(clock);
    }

    @Bean
    WebhookEventStore webhookEventStore(JdbcClient jdbc) {
        return new JdbcWebhookEventStore(jdbc);
    }

    @Bean
    WebhookIntakeUseCase webhookIntakeUseCase(WebhookEventStore webhookEventStore,
            PaymentRepository paymentRepository, OutboxWriter outboxWriter,
            AuditWriter auditWriter, WebhookSignatureValidator webhookSignatureValidator,
            TransactionTemplate transactionTemplate,
            EventSerializer eventSerializer, Clock clock,
            ObjectMapper objectMapper) {
        return new WebhookIntakeUseCase(webhookEventStore, paymentRepository, outboxWriter, auditWriter,
                webhookSignatureValidator, transactionTemplate, eventSerializer, clock, objectMapper);
    }

    @Bean
    public WebhookController webhookController(WebhookIntakeUseCase webhookIntakeUseCase,
            WebhookSignatureValidator webhookSignatureValidator,
            WebhookEventStore webhookEventStore,
            ErrorResponseWriter errorWriter,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${dargent.psp.webhook-secret}") String secret) {
        return new WebhookController(webhookIntakeUseCase, webhookSignatureValidator,
                webhookEventStore, errorWriter, objectMapper, clock, secret);
    }

    @Bean
    PspPort pspPort(@Value("${dargent.psp.base-url}") String baseUrl,
            @Value("${dargent.psp.create-max-attempts}") int maxAttempts,
            @Value("${dargent.psp.create-backoff-base-ms}") long backoffBaseMs) {
        return new SimulatorChargeAdapter(baseUrl, maxAttempts, Duration.ofMillis(backoffBaseMs),
                () -> backoffBaseMs);
    }

    @Bean
    CreatePaymentUseCase createPaymentUseCase(PaymentRepository paymentRepository,
            IdempotencyStore idempotencyStore, OutboxWriter outboxWriter, AuditWriter auditWriter,
            PspPort pspPort, TxidGenerator txidGenerator, TransactionTemplate transactionTemplate,
            EventSerializer eventSerializer, Clock clock,
            @Value("${dargent.pix.profile.pix-key}") String pixKey,
            @Value("${dargent.pix.profile.receiver-name}") String receiverName,
            @Value("${dargent.pix.profile.receiver-city}") String receiverCity,
            @Value("${dargent.psp.callback-url}") String pspCallbackUrl) {
        return new CreatePaymentUseCase(paymentRepository, idempotencyStore, outboxWriter, auditWriter,
                pspPort, txidGenerator, transactionTemplate, eventSerializer, pixKey, receiverName,
                receiverCity, pspCallbackUrl, clock);
    }

    // --- E6 outbox relay beans ---

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
    OutboxEventStore outboxEventStore(JdbcClient jdbc) {
        return new JdbcOutboxEventStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
    EventPublisher snsEventPublisher(@Value("${DARGENT_EVENTS_TOPIC_ARN}") String topicArn,
            @Value("${DARGENT_EVENTS_PUBLISH_TIMEOUT_MS}") long timeoutMs,
            @Value("${AWS_REGION}") String region,
            @Value("${AWS_ENDPOINT_URL}") String endpointUrl,
            @Value("${AWS_ACCESS_KEY_ID:test}") String accessKey,
            @Value("${AWS_SECRET_ACCESS_KEY:test}") String secretKey) {
        return new SnsEventPublisher(topicArn, timeoutMs, region, endpointUrl, accessKey, secretKey);
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "false", matchIfMissing = true)
    EventPublisher noopEventPublisher() {
        return new EventPublisher() {
            @Override
            public void publish(String type, String payload, String eventId, String aggregateId) {
                // No-op for when the relay is disabled
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
    OutboxDeliveryUseCase outboxDeliveryUseCase(OutboxEventStore store,
            EventPublisher publisher, ObjectMapper mapper, Clock clock,
            OutboxDeliveryUseCase.Policy policy,
            TransactionTemplate transactionTemplate) {
        return new OutboxDeliveryUseCase(store, publisher, mapper, clock, policy, transactionTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
    OutboxDeliveryUseCase.Policy outboxDeliveryPolicy(
            @Value("${DARGENT_RELAY_BATCH}") int batch,
            @Value("${DARGENT_RELAY_WORKERS}") int workers,
            @Value("${DARGENT_RELAY_POLL_MS}") long pollMs) {
        return new OutboxDeliveryUseCase.Policy(batch, workers, pollMs,
                Integer.MAX_VALUE, Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    @Bean
    @ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
    TaskScheduler relayScheduler(OutboxDeliveryUseCase useCase, OutboxDeliveryUseCase.Policy policy, Clock clock) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(policy.workers());
        scheduler.setThreadNamePrefix("outbox-relay-");
        scheduler.initialize();
        scheduler.scheduleAtFixedRate(
                () -> useCase.runOnce(policy.batchSize()),
                clock.instant(),
                Duration.ofMillis(policy.pollMs())
        );
        return scheduler;
    }
}

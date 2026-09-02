package io.dargent.api.config;

import io.dargent.notifications.adapter.out.db.JdbcNotificationStore;
import io.dargent.notifications.application.EventEnvelopeReader;
import io.dargent.notifications.application.NotificationIngestionUseCase;
import io.dargent.notifications.domain.port.out.NotificationStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notifications application beans (E10 spec §4). Always-on so the ledger HTTP surface and the SQS
 * consumer share one bean graph regardless of {@code DARGENT_NOTIFS_CONSUMER_ENABLED}.
 * Adapters/use cases are module classes; this class only wires them (AGENTS §2).
 */
@Configuration
public class NotificationApplicationConfig {

    @Bean
    NotificationStore notificationStore(JdbcClient jdbc, TransactionTemplate txTemplate) {
        return new JdbcNotificationStore(jdbc);
    }

    @Bean
    EventEnvelopeReader notificationEventEnvelopeReader() {
        return new EventEnvelopeReader();
    }

    @Bean
    NotificationIngestionUseCase notificationIngestionUseCase(EventEnvelopeReader reader, NotificationStore store,
            JdbcClient jdbc, TransactionTemplate txTemplate, Clock clock) {
        return new NotificationIngestionUseCase(reader, store, jdbc, clock);
    }
}
package io.dargent.api.config;

import io.dargent.notifications.adapter.out.db.JdbcNotificationQuery;
import io.dargent.notifications.adapter.out.db.JdbcNotificationStore;
import io.dargent.notifications.application.EventEnvelopeReader;
import io.dargent.notifications.application.NotificationIngestionUseCase;
import io.dargent.notifications.domain.port.out.NotificationQueryPort;
import io.dargent.notifications.domain.port.out.NotificationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Notifications application beans (E10 spec §4). Always-on application beans for the
 * notification consumer and read side; SQS only when composition config enables it.
 * Adapters/use cases are module classes; this class only wires them (AGENTS §2).
 */
@Configuration
public class NotificationApplicationConfig {

    @Bean
    NotificationStore notificationStore(JdbcTemplate jdbcTemplate) {
        return new JdbcNotificationStore(jdbcTemplate);
    }

    @Bean
    NotificationQueryPort notificationQueryPort(JdbcTemplate jdbcTemplate) {
        return new JdbcNotificationQuery(jdbcTemplate);
    }

    @Bean
    EventEnvelopeReader notificationEventEnvelopeReader() {
        return new EventEnvelopeReader();
    }

    @Bean
    NotificationIngestionUseCase notificationIngestionUseCase(EventEnvelopeReader reader, NotificationStore store) {
        return new NotificationIngestionUseCase(reader, store);
    }
}
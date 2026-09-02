package io.dargent.notifications.adapter.out.db;

import io.dargent.notifications.domain.port.out.NotificationStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

/**
 * JDBC implementation of NotificationStore (E10 spec §5).
 * Uses Spring JdbcClient — zero JPA, zero Hibernate.
 */
public final class JdbcNotificationStore implements NotificationStore {

    private final JdbcClient jdbc;

    public JdbcNotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertNotificationIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
            String payload, Instant occurredAt) {
        int rows = jdbc.sql("""
                INSERT INTO notifications.notification (id, event_id, type, txid, merchant_id, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .params(UUID.randomUUID(), eventId, type, txid, merchantId, payload, occurredAt)
                .update();
        return rows > 0;
    }
}
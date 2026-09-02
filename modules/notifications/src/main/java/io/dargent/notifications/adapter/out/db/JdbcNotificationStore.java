package io.dargent.notifications.adapter.out.db;

import io.dargent.notifications.domain.port.out.NotificationStore;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.SQLException;
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
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        try {
            jsonb.setValue(payload);
        } catch (SQLException e) {
            throw new IllegalStateException("Invalid JSON payload for jsonb column", e);
        }

        int rows = jdbc.sql("""
                INSERT INTO notifications.notification (id, event_id, type, txid, merchant_id, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .params(UUID.randomUUID(), eventId, type, txid, merchantId, jsonb, occurredAt)
                .update();
        return rows > 0;
    }
}
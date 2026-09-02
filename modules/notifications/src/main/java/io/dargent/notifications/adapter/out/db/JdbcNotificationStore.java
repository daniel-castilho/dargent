package io.dargent.notifications.adapter.out.db;

import io.dargent.notifications.domain.port.out.NotificationStore;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * JDBC implementation of NotificationStore (E10 spec §5).
 * Uses Spring JdbcTemplate with PGobject for jsonb binding (CI-compatible).
 */
public final class JdbcNotificationStore implements NotificationStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertNotificationIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
            String payload, Instant occurredAt) {
        String sql = """
                INSERT INTO notifications.notification (id, event_id, type, txid, merchant_id, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """;

        int rows = jdbcTemplate.update(sql, new PreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps) throws SQLException {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, eventId);
                ps.setString(3, type);
                ps.setString(4, txid);
                ps.setObject(5, merchantId);

                PGobject jsonb = new PGobject();
                jsonb.setType("jsonb");
                try {
                    jsonb.setValue(payload);
                } catch (SQLException e) {
                    throw new IllegalStateException("Invalid JSON payload for jsonb column", e);
                }
                ps.setObject(6, jsonb, Types.OTHER);

                ps.setObject(7, occurredAt);
            }
        });
        return rows > 0;
    }
}
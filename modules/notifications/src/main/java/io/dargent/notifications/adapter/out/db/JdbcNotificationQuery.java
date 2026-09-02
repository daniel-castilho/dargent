package io.dargent.notifications.adapter.out.db;

import io.dargent.notifications.domain.model.NotificationView;
import io.dargent.notifications.domain.port.out.NotificationQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JDBC read-side implementation of {@link NotificationQueryPort} (E10 spec §5 read side).
 * Keyset pagination over {@code (created_at, id) DESC} — stable under insertion because {@code id}
 * breaks ties within the same {@code created_at} microsecond. Never selects {@code payload}.
 */
public final class JdbcNotificationQuery implements NotificationQueryPort {

    private static final String BASE = """
            select id, event_id, type, txid, merchant_id, occurred_at, created_at
            from notifications.notification
            """;

    private static final RowMapper<NotificationView> MAPPER = (rs, i) -> new NotificationView(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getString("type"),
            rs.getString("txid"),
            rs.getObject("merchant_id", UUID.class),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcNotificationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NotificationView> findPage(UUID merchantId, String type, String cursor, int limit) {
        StringBuilder sql = new StringBuilder(BASE).append("where merchant_id = ?");
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(merchantId);

        if (type != null && !type.isBlank()) {
            sql.append(" and type = ?");
            params.add(type);
        }

        if (cursor != null && !cursor.isBlank()) {
            // cursor = "createdAtMicros|<id>" — already decoded once by the controller
            String[] parts = cursor.split("\\|", 2);
            long afterMicros = Long.parseLong(parts[0]);
            UUID afterId = UUID.fromString(parts[1]);
            sql.append(" and (created_at < ? or (created_at = ? and id < ?))");
            params.add(Timestamp.from(Instant.ofEpochMilli(afterMicros / 1000)));
            params.add(Timestamp.from(Instant.ofEpochMilli(afterMicros / 1000)));
            params.add(afterId);
        }

        sql.append(" order by created_at desc, id desc limit ?");
        params.add(limit);

        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }
}

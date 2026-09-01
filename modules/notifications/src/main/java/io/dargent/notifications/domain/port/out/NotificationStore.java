package io.dargent.notifications.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for notifications persistence (E10 spec §5).
 * One implementation (JdbcNotificationStore) — no second access path.
 */
public interface NotificationStore {

    /**
     * Inserts a notification if event_id is new (idempotency).
     * Returns true if inserted, false if duplicate (event_id already exists).
     */
    boolean insertNotificationIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
            String payload, Instant occurredAt);
}
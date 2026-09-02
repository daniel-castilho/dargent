package io.dargent.notifications.domain.port.out;

import io.dargent.notifications.domain.model.NotificationView;
import java.util.List;
import java.util.UUID;

/**
 * Read-side port for the notifications list API (E10 spec §5 read side, §7).
 * Tenant-scoped by merchantId (supplied from the authenticated principal — AGENTS §3.7).
 */
public interface NotificationQueryPort {

    /**
     * Lists notifications for a merchant with cursor pagination.
     * Ordered by {@code created_at DESC, id DESC} (stable under insertion). Cursor is the
     * already-decoded keyset {@code "createdAtMicros|<id>"} (the controller decodes the opaque
     * base64 cursor once). Null/blank cursor means the first page. {@code type} optionally filters.
     */
    List<NotificationView> findPage(UUID merchantId, String type, String cursor, int limit);
}

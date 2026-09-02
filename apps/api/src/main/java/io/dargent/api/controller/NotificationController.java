package io.dargent.api.controller;

import io.dargent.api.error.RequestValidationException;
import io.dargent.api.security.ApiKeyPrincipal;
import io.dargent.api.web.NotificationCursorCodec;
import io.dargent.notifications.domain.model.NotificationView;
import io.dargent.notifications.domain.port.out.NotificationQueryPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notifications read HTTP surface (E10 spec §7). {@code GET /v1/notifications} lists notifications
 * for the authenticated tenant (AGENTS §3.7 — merchant comes from the principal, never query/path/body).
 * {@code payload} is never emitted. Cursor is an opaque keyset token decoded once (BD-10 pattern).
 * Authenticated via API key (SecurityConfig); route declared explicitly (AGENTS §4.1).
 */
@RestController
@RequestMapping("/v1/notifications")
class NotificationController {

    private final NotificationQueryPort queryPort;

    NotificationController(NotificationQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @GetMapping
    ResponseEntity<NotificationListResponse> list(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {

        int clamped = clampLimit(limit);
        String keyset = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                NotificationCursorCodec.Decoded decoded = NotificationCursorCodec.decode(cursor);
                keyset = decoded.createdAtMicros() + "|" + decoded.id();
            } catch (IllegalArgumentException e) {
                throw new RequestValidationException(Map.of("cursor", "invalid cursor"));
            }
        }

        List<NotificationView> page = queryPort.findPage(principal.merchantId(), type, keyset, clamped);
        String nextCursor = null;
        if (page.size() == clamped) {
            NotificationView last = page.get(page.size() - 1);
            nextCursor = NotificationCursorCodec.encode(last.createdAt(), last.id());
        }
        List<NotificationItemResponse> items = page.stream()
                .map(n -> new NotificationItemResponse(
                        n.id(), n.eventId(), n.type(), n.txid(), n.merchantId(), n.occurredAt(), n.createdAt()))
                .toList();
        return ResponseEntity.ok(new NotificationListResponse(items, nextCursor));
    }

    private int clampLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return 20;
        }
        int value;
        try {
            value = Integer.parseInt(limit);
        } catch (NumberFormatException e) {
            throw new RequestValidationException(Map.of("limit", "must be an integer"));
        }
        if (value < 1) {
            throw new RequestValidationException(Map.of("limit", "must be at least 1"));
        }
        return Math.min(value, 100);
    }

    record NotificationItemResponse(
            UUID id,
            UUID eventId,
            String type,
            String txid,
            UUID merchantId,
            Instant occurredAt,
            Instant createdAt) {}

    record NotificationListResponse(
            List<NotificationItemResponse> data,
            String nextCursor) {}
}

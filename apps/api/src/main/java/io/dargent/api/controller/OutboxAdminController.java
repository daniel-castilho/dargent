package io.dargent.api.controller;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import io.dargent.api.security.ApiKeyAuthenticationFilter;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.security.ApiKeyPrincipal;
import io.dargent.api.web.RequestIdFilter;
import io.dargent.payments.domain.model.OutboxId;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import io.dargent.payments.domain.port.out.OutboxEventStore.RequeueOutcome;
import io.dargent.payments.domain.port.out.OutboxEventStore.RequeueResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin outbox surface (E9 §3/§4): human recovery actions on the transactional outbox, gated by a
 * dedicated admin key value. Both endpoints 404-hidden when {@code DARGENT_OUTBOX_ADMIN_KEY} is
 * unset; the acting caller must present that admin key (a real API key) — the audit trail records
 * the real principal, never the SYSTEM sentinel. Every transition stays conditional on the row's
 * status; the audit insert runs in the same transaction as the state change.
 */
@RestController
@RequestMapping("/v1/outbox")
@ConditionalOnProperty(name = "dargent.relay.enabled", havingValue = "true", matchIfMissing = false)
class OutboxAdminController {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminController.class);

    private final OutboxEventStore store;
    private final AuditWriter auditWriter;
    private final ErrorResponseWriter errorWriter;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final String adminKey;

    OutboxAdminController(OutboxEventStore store, AuditWriter auditWriter,
            ErrorResponseWriter errorWriter, TransactionTemplate txTemplate, Clock clock,
            @Value("${DARGENT_OUTBOX_ADMIN_KEY:}") String adminKey) {
        this.store = store;
        this.auditWriter = auditWriter;
        this.errorWriter = errorWriter;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.adminKey = adminKey;
    }

    /**
     * E9 §3: requeue an EXHAUSTED outbox row — conditional {@code EXHAUSTED→PENDING} with
     * {@code attempt_count=0} and {@code next_attempt_at=now()}, audited as {@code outbox_requeued}
     * by the real admin principal. 1 row → 200; present-but-not-exhaustible → 409
     * {@code not_exhaustible}; unknown id → 404.
     */
    @PostMapping("/{id}/requeue")
    void requeue(HttpServletRequest request, HttpServletResponse response,
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable String id)
            throws IOException {
        if (!isAdmin(request, response, principal)) {
            return;
        }
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        OutboxId outboxId = parseOutboxId(request, response, id);
        if (outboxId == null) {
            return;
        }
        final UUID adminKeyId = principal.keyId();
        final UUID adminMerchantId = principal.merchantId();

        RequeueResult result = txTemplate.execute(status -> {
            RequeueResult r = store.requeueExhausted(outboxId, clock.instant());
            if (r.outcome() == RequeueOutcome.REQUEUED) {
                auditWriter.record("outbox_requeued", adminKeyId, adminMerchantId,
                        r.aggregateId(), requestId);
            }
            return r;
        });

        if (result.outcome() == RequeueOutcome.REQUEUED) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"id\":\"" + outboxId.value()
                    + "\",\"status\":\"PENDING\",\"attemptCount\":0}");
            return;
        }
        if (result.outcome() == RequeueOutcome.NOT_EXHAUSTIBLE) {
            errorWriter.write(request, response, ErrorCode.NOT_EXHAUSTIBLE,
                    "Outbox row is not exhausted", (java.util.Map<String, String>) null);
            return;
        }
        errorWriter.write(request, response, ErrorCode.NOT_FOUND,
                "Outbox row not found", (java.util.Map<String, String>) null);
    }

    /**
     * Admin gate (E9 §4.1): when {@code DARGENT_OUTBOX_ADMIN_KEY} is unset/blank the route is
     * 404-hidden; otherwise the caller must present that exact key (constant-time compare). A valid
     * API key that is not the admin key is 403.
     */
    private boolean isAdmin(HttpServletRequest request, HttpServletResponse response,
            ApiKeyPrincipal principal) throws IOException {
        if (adminKey == null || adminKey.isBlank()) {
            errorWriter.write(request, response, ErrorCode.NOT_FOUND, "Unknown route",
                    (java.util.Map<String, String>) null);
            return false;
        }
        String rawKey = (String) request.getAttribute(ApiKeyAuthenticationFilter.RAW_KEY_ATTRIBUTE);
        if (rawKey == null
                || !ApiKeyHasher.constantTimeEquals(ApiKeyHasher.hash(adminKey), ApiKeyHasher.hash(rawKey))) {
            log.warn("forbidden admin outbox action by key {} merchant {}", principal.keyId(), principal.merchantId());
            errorWriter.write(request, response, ErrorCode.FORBIDDEN, "Forbidden",
                    (java.util.Map<String, String>) null);
            return false;
        }
        return true;
    }

    /** Parses the path {@code id} as a UUID and emits 404 on a malformed value. */
    private OutboxId parseOutboxId(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        try {
            return new OutboxId(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            errorWriter.write(request, response, ErrorCode.NOT_FOUND, "Outbox row not found",
                    (java.util.Map<String, String>) null);
            return null;
        }
    }
}
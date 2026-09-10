package io.dargent.api.controller;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import io.dargent.api.security.ApiKeyAuthenticationFilter;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.security.ApiKeyPrincipal;
import io.dargent.api.web.RequestIdFilter;
import io.dargent.payments.application.WebhookIntakeUseCase;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Admin webhook surface (M5 S4): re-drive a stored {@code webhook_events} row through the intake
 * path. Gated by a DEDICATED admin key value ({@code DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY});
 * 404-hidden when unset (house pattern); the acting caller must present that key — the audit trail
 * records the REAL principal, never the SYSTEM sentinel. Idempotent by design: the
 * {@code provider_event_id} UNIQUE makes a re-run of the tool a no-op (duplicate) once processed,
 * so double-confirm/double-journal is impossible (mirrors E9 requeue semantics).
 *
 * <p>Responses: 200 {@code {processed|duplicate|ignored}}, 404 unknown row (and route-hidden when
 * unset), 409 {@code invalid_state} for signature-invalid (attack) evidence — fail-closed (AGENTS
 * §4.4), 401/403 via the house ladder.
 */
@RestController
@RequestMapping("/v1/webhooks")
class WebhookReprocessAdminController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReprocessAdminController.class);

    private final WebhookIntakeUseCase useCase;
    private final AuditWriter auditWriter;
    private final ErrorResponseWriter errorWriter;
    private final TransactionTemplate txTemplate;
    private final MeterRegistry metrics;
    private final ObjectMapper objectMapper;
    private final String adminKey;

    WebhookReprocessAdminController(
            WebhookIntakeUseCase useCase,
            AuditWriter auditWriter,
            ErrorResponseWriter errorWriter,
            TransactionTemplate txTemplate,
            MeterRegistry metrics,
            ObjectMapper objectMapper,
            @Value("${DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY:}") String adminKey) {
        this.useCase = useCase;
        this.auditWriter = auditWriter;
        this.errorWriter = errorWriter;
        this.txTemplate = txTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.adminKey = adminKey;
    }

    /**
     * Re-drive a stored webhook event through the intake core. The row mutation, the confirm and the
     * {@code webhook_reprocessed} audit run in ONE transaction (audit only on a real state change).
     */
    @PostMapping("/reprocess")
    void reprocess(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal ApiKeyPrincipal principal)
            throws IOException {
        if (!isAdmin(request, response, principal)) {
            return;
        }
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String providerEventId = parseProviderEventId(request, response);
        if (providerEventId == null) {
            return;
        }
        final UUID adminKeyId = principal.keyId();
        final UUID adminMerchantId = principal.merchantId();

        WebhookIntakeUseCase.ReprocessResult result = txTemplate.execute(status -> {
            WebhookIntakeUseCase.ReprocessResult r = useCase.reprocess(providerEventId);
            if (r instanceof WebhookIntakeUseCase.ReprocessResult.ReProcessed p && p.txid() != null) {
                auditWriter.record("webhook_reprocessed", adminKeyId, adminMerchantId, p.txid(), requestId);
            } else if (r instanceof WebhookIntakeUseCase.ReprocessResult.IgnoredReProcess i && i.txid() != null) {
                auditWriter.record("webhook_reprocessed", adminKeyId, adminMerchantId, i.txid(), requestId);
            }
            return r;
        });

        switch (result) {
            case WebhookIntakeUseCase.ReprocessResult.ReProcessed ignored -> {
                metrics.counter("dargent.webhook.reprocess", "outcome", "processed")
                        .increment();
                log.info("Webhook reprocessed: provider_event_id={}, request_id={}", providerEventId, requestId);
                write(response, "processed", null);
            }
            case WebhookIntakeUseCase.ReprocessResult.AlreadyProcessed ignored -> {
                metrics.counter("dargent.webhook.reprocess", "outcome", "duplicate")
                        .increment();
                log.info("Webhook reprocess no-op (already processed): provider_event_id={}", providerEventId);
                write(response, "duplicate", null);
            }
            case WebhookIntakeUseCase.ReprocessResult.IgnoredReProcess i -> {
                metrics.counter("dargent.webhook.reprocess", "outcome", "ignored")
                        .increment();
                log.info("Webhook reprocess ignored: provider_event_id={}, reason={}", providerEventId, i.reason());
                write(response, "ignored", i.reason());
            }
            case WebhookIntakeUseCase.ReprocessResult.AttackEvidence ignored -> {
                metrics.counter("dargent.webhook.reprocess", "outcome", "attack_evidence")
                        .increment();
                log.warn("webhook reprocess refused on attack evidence: provider_event_id={}", providerEventId);
                errorWriter.write(
                        request,
                        response,
                        ErrorCode.INVALID_STATE,
                        "Webhook event is signature-invalid attack evidence and cannot be re-driven");
            }
            case WebhookIntakeUseCase.ReprocessResult.NotFound ignored -> {
                metrics.counter("dargent.webhook.reprocess", "outcome", "not_found")
                        .increment();
                errorWriter.write(request, response, ErrorCode.NOT_FOUND, "Webhook event not found");
            }
        }
    }

    /**
     * Admin gate (house pattern, E9 §4.1): when {@code DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY} is
     * unset/blank the route is 404-hidden; otherwise the caller must present that exact key
     * (constant-time compare). A valid API key that is not the admin key is 403.
     */
    private boolean isAdmin(HttpServletRequest request, HttpServletResponse response, ApiKeyPrincipal principal)
            throws IOException {
        if (adminKey == null || adminKey.isBlank()) {
            errorWriter.write(request, response, ErrorCode.NOT_FOUND, "Unknown route");
            return false;
        }
        String rawKey = (String) request.getAttribute(ApiKeyAuthenticationFilter.RAW_KEY_ATTRIBUTE);
        if (rawKey == null
                || !ApiKeyHasher.constantTimeEquals(ApiKeyHasher.hash(adminKey), ApiKeyHasher.hash(rawKey))) {
            log.warn(
                    "forbidden admin webhook reprocess by key {} merchant {}",
                    principal.keyId(),
                    principal.merchantId());
            errorWriter.write(request, response, ErrorCode.FORBIDDEN, "Forbidden");
            return false;
        }
        return true;
    }

    /** Parses the body {@code {"providerEventId": "..."}}; emits 400 on a malformed request. */
    private String parseProviderEventId(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode node;
        try (var reader = request.getReader()) {
            String body = reader.lines().reduce("", (a, b) -> a + b);
            if (body.isBlank()) {
                errorWriter.write(request, response, ErrorCode.INVALID_REQUEST, "Missing request body");
                return null;
            }
            node = new JsonMapper().readTree(body);
        } catch (JacksonException e) {
            errorWriter.write(request, response, ErrorCode.INVALID_REQUEST, "Body is not valid JSON");
            return null;
        }
        String providerEventId = node.path("providerEventId").asText(null);
        if (providerEventId == null || providerEventId.isBlank()) {
            errorWriter.write(request, response, ErrorCode.INVALID_REQUEST, "Missing field: providerEventId");
            return null;
        }
        return providerEventId;
    }

    private void write(HttpServletResponse response, String status, String reason) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", status);
        if (reason != null) {
            body.put("reason", reason);
        }
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

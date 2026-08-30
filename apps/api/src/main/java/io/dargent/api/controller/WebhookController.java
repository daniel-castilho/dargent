package io.dargent.api.controller;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import io.dargent.payments.application.WebhookIntakeUseCase;
import io.dargent.payments.domain.model.WebhookSignatureValidator;
import io.dargent.payments.domain.port.out.WebhookEventRecord;
import io.dargent.payments.domain.port.out.WebhookEventStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Webhook intake endpoint (E4 spec §5.1): fail-closed HMAC, anti-replay, dedupe, conditional confirm.
 * Raw body captured ONCE; persisted before any 401 (attack audit); validated via injected validator + clock.
 * /webhooks/psp is permitAll — HMAC is the auth.
 */
@RestController
@RequestMapping("/webhooks/psp")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookIntakeUseCase useCase;
    private final WebhookSignatureValidator validator;
    private final WebhookEventStore webhookEventStore;
    private final ErrorResponseWriter errorWriter;
    private final ObjectMapper objectMapper;
    private final String secret;

    public WebhookController(WebhookIntakeUseCase useCase,
            WebhookSignatureValidator validator,
            WebhookEventStore webhookEventStore,
            ErrorResponseWriter errorWriter,
            ObjectMapper objectMapper,
            @Value("${dargent.psp.webhook-secret}") String secret) {
        this.useCase = useCase;
        this.validator = validator;
        this.webhookEventStore = webhookEventStore;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
        this.secret = secret;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    void receive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 1. Capture raw body bytes ONCE (E4 spec §5.1 step 1)
        byte[] rawBody = request.getInputStream().readAllBytes();

        // 2. Extract headers
        String timestamp = request.getHeader("X-PSP-Timestamp");
        String signature = request.getHeader("X-PSP-Signature");

        // 3. Validate signature (fail-closed) — but PERSIST raw first on failure (E4 spec §5.1 step 2/3)
        var verdict = validator.verify(
                timestamp == null ? "" : timestamp,
                rawBody,
                signature == null ? "" : signature,
                secret);

        if (verdict == WebhookSignatureValidator.Verdict.INVALID) {
            persistRawAndRespond(request, response, rawBody, timestamp, signature, false,
                    "INVALID_SIGNATURE", "Invalid signature");
            return;
        }
        if (verdict == WebhookSignatureValidator.Verdict.EXPIRED) {
            persistRawAndRespond(request, response, rawBody, timestamp, signature, false,
                    "SIGNATURE_EXPIRED", "Signature expired");
            return;
        }

        // 4. Parse minimal fields from rawBody for provider_event_id (no full deserialization yet)
        String type = extractJsonField(new String(rawBody, java.nio.charset.StandardCharsets.UTF_8), "type");
        String endToEndId = extractJsonField(new String(rawBody, java.nio.charset.StandardCharsets.UTF_8), "endToEndId");
        String txid = extractJsonField(new String(rawBody, java.nio.charset.StandardCharsets.UTF_8), "txid");

        String providerEventId = endToEndId + "|" + type;

        // 5. Delegate to use case (single transaction, order fixed per §5.3)
        var outcome = useCase.execute(new WebhookIntakeUseCase.Input(
                providerEventId,
                extractJsonField(new String(rawBody, java.nio.charset.StandardCharsets.UTF_8), "eventId"),
                type,
                txid,
                new String(rawBody, java.nio.charset.StandardCharsets.UTF_8),
                true
        ));

        // 6. Map outcome to response
        switch (outcome) {
            case WebhookIntakeUseCase.Outcome.Processed ignored -> writeSuccess(response, "processed");
            case WebhookIntakeUseCase.Outcome.Duplicate ignored -> writeSuccess(response, "duplicate");
            case WebhookIntakeUseCase.Outcome.Ignored i -> {
                log.warn("Webhook ignored: provider_event_id={}, reason={}", providerEventId, i.reason());
                writeSuccess(response, "ignored");
            }
        }
    }

    private void persistRawAndRespond(HttpServletRequest request, HttpServletResponse response,
            byte[] rawBody, String timestamp, String signature, boolean signatureValid,
            String errorCode, String detail) throws IOException {
        // Persist raw body with signature_valid=false (attack audit) BEFORE responding 401
        String providerEventId = "unknown|" + (timestamp != null ? timestamp : "no-timestamp");
        // We don't have type/endToEndId easily here; store minimal record with what we have
        webhookEventStore.insertIfAbsent(new WebhookEventRecord(
                UUID.randomUUID(),
                providerEventId,
                "unknown",
                "unknown",
                null,
                new String(rawBody, java.nio.charset.StandardCharsets.UTF_8),
                signatureValid,
                "IGNORED", // invalid signature → not processed
                java.time.Instant.now(),
                java.time.Instant.now()
        ));

        // Now respond 401 via ErrorResponseWriter
        errorWriter.write(request, response, ErrorCode.valueOf(errorCode), detail);
    }

    private void writeSuccess(HttpServletResponse response, String status) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", status);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String extractJsonField(String json, String field) {
        String search = "\"" + field + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return "";
        int start = idx + search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : "";
        } else {
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) end++;
            return json.substring(start, end);
        }
    }
}
package io.dargent.api.controller;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import io.dargent.api.web.RequestIdFilter;
import io.dargent.payments.application.WebhookIntakeUseCase;
import io.dargent.payments.domain.model.WebhookSignatureValidator;
import io.dargent.payments.domain.port.out.WebhookEventRecord;
import io.dargent.payments.domain.port.out.WebhookEventStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
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
    private static final String RAW_PREFIX = "raw|";

    private final WebhookIntakeUseCase useCase;
    private final WebhookSignatureValidator validator;
    private final WebhookEventStore webhookEventStore;
    private final ErrorResponseWriter errorWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String secret;

    public WebhookController(WebhookIntakeUseCase useCase,
            WebhookSignatureValidator validator,
            WebhookEventStore webhookEventStore,
            ErrorResponseWriter errorWriter,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${dargent.psp.webhook-secret}") String secret) {
        this.useCase = useCase;
        this.validator = validator;
        this.webhookEventStore = webhookEventStore;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secret = secret;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    void receive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();

        String timestamp = request.getHeader("X-PSP-Timestamp");
        String signature = request.getHeader("X-PSP-Signature");

        var verdict = validator.verify(
                timestamp == null ? "" : timestamp,
                rawBody,
                signature == null ? "" : signature,
                secret);

        if (verdict == WebhookSignatureValidator.Verdict.INVALID) {
            persistRawAndRespond(request, response, rawBody, false,
                    "INVALID_SIGNATURE", "Invalid signature");
            return;
        }
        if (verdict == WebhookSignatureValidator.Verdict.EXPIRED) {
            persistRawAndRespond(request, response, rawBody, false,
                    "SIGNATURE_EXPIRED", "Signature expired");
            return;
        }

        JsonNode parsed = objectMapper.readTree(rawBody);
        String type = text(parsed, "type");
        String endToEndId = text(parsed, "endToEndId");
        String txid = text(parsed, "txid");
        String eventId = text(parsed, "eventId");

        String providerEventId = endToEndId + "|" + type;

        log.info("Webhook intake type={} provider_event_id={} txid={}",
                type, providerEventId, txid);

        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);

        var outcome = useCase.execute(new WebhookIntakeUseCase.Input(
                providerEventId,
                eventId,
                type,
                txid,
                new String(rawBody, StandardCharsets.UTF_8),
                true,
                requestId
        ));

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
            byte[] rawBody, boolean signatureValid, String errorCode, String detail) throws IOException {
        String providerEventId = RAW_PREFIX + sha256Hex(rawBody);
        webhookEventStore.insertIfAbsent(new WebhookEventRecord(
                UUID.randomUUID(),
                providerEventId,
                "unknown",
                "unknown",
                null,
                new String(rawBody, StandardCharsets.UTF_8),
                signatureValid,
                "IGNORED",
                clock.instant(),
                clock.instant()
        ));

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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

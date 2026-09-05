package io.dargent.api.controller;

import io.dargent.api.error.RequestValidationException;
import io.dargent.api.web.CursorCodec;
import io.dargent.api.web.RequestIdFilter;
import io.dargent.payments.application.CreatePaymentUseCase;
import io.dargent.payments.application.RefundPaymentUseCase;
import io.dargent.payments.domain.br.BrCode;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PaymentQueryPort;
import io.dargent.shared.money.Money;
import io.dargent.shared.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment HTTP surface (E3 spec §5.1–§5.3; E3R R3): {@code POST /v1/payments} (idempotent create) plus the
 * read side. Authenticated via API key (SecurityConfig); tenant derived from principal (AGENTS §3.7).
 * BD-10: the BR Code and PIX profile come from the configured values, {@code Clock} is injected (never
 * {@code Instant.now()} in request paths), and the list cursor is decoded once to a keyset before
 * {@code findPage}.
 */
@RestController
@RequestMapping("/v1/payments")
class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final String ENDPOINT = "POST /v1/payments";
    private static final String BRL = "BRL";

    private final PaymentQueryPort queryPort;
    private final CreatePaymentUseCase createUseCase;
    private final RefundPaymentUseCase refundUseCase;
    private final Clock clock;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final String pixKey;
    private final String receiverName;
    private final String receiverCity;

    PaymentController(PaymentQueryPort queryPort, CreatePaymentUseCase createUseCase,
            RefundPaymentUseCase refundUseCase, Clock clock,
            tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${dargent.pix.profile.pix-key}") String pixKey,
            @Value("${dargent.pix.profile.receiver-name}") String receiverName,
            @Value("${dargent.pix.profile.receiver-city}") String receiverCity) {
        this.queryPort = queryPort;
        this.createUseCase = createUseCase;
        this.refundUseCase = refundUseCase;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.pixKey = pixKey;
        this.receiverName = receiverName;
        this.receiverCity = receiverCity;
    }

    @PostMapping
    ResponseEntity<CreatePaymentResponse> create(
            HttpServletRequest request,
            @AuthenticationPrincipal io.dargent.api.security.ApiKeyPrincipal principal) {
        byte[] rawBody = readRawBody(request);
        String fingerprint = fingerprint(rawBody);
        CreatePaymentRequest body = parseBody(rawBody);
        long amount = body.validatedAmount();
        String description = body.validatedDescription();
        Duration expiresIn = body.parsedExpiresIn();
        String idempotencyKey = idempotencyKey(request);
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);

        log.info("Payment create request endpoint={} merchant_id={} amount_cents={} expires_in={}",
                ENDPOINT, principal.merchantId(), amount, expiresIn);

        CreatePaymentUseCase.Output out = createUseCase.execute(new CreatePaymentUseCase.Input(principal.merchantId(), principal.keyId(),
                idempotencyKey, ENDPOINT, fingerprint, requestId, Money.of(amount, BRL),
                description, expiresIn));

        MDC.put("txid", out.txid().value());
        log.info("Payment create result status={} idempotent_replay={}",
                out.status().name(), out.replay());
        MDC.remove("txid");

        var response = new CreatePaymentResponse(out.txid().value(), out.status().name(), amount,
                BRL, out.expiresAt(), out.brcode(), Duration.between(clock.instant(), out.expiresAt()));
        ResponseEntity.BodyBuilder builder = org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .location(java.net.URI.create("/v1/payments/" + out.txid().value()));
        if (out.replay()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(response);
    }

    @GetMapping("/{txid}")
    ResponseEntity<PaymentDetailResponse> detail(
            @AuthenticationPrincipal io.dargent.api.security.ApiKeyPrincipal principal,
            @PathVariable String txid) {
        Txid txidObj = new Txid(txid);
        Optional<Payment> payment = queryPort.findByTxid(principal.merchantId(), txidObj);
        if (payment.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Payment p = payment.get();
        return ResponseEntity.ok(new PaymentDetailResponse(
                p.txid().value(),
                p.status().name(),
                p.amount().cents(),
                BRL,
                p.expiresAt(),
                BrCode.of(pixKey, receiverName, receiverCity, p.amount().cents(), p.txid()),
                Duration.between(clock.instant(), p.expiresAt())));
    }

    @GetMapping
    ResponseEntity<PaymentListResponse> list(
            @AuthenticationPrincipal io.dargent.api.security.ApiKeyPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        int clampedLimit = Math.min(Math.max(limit, 1), 100);
        String keyset = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorCodec.Decoded decoded = CursorCodec.decode(cursor); // validate + decode once (BD-10)
            keyset = decoded.txid() + "|" + decoded.createdAt().toEpochMilli() * 1000;
        }
        List<Payment> payments = queryPort.findPage(principal.merchantId(), keyset, clampedLimit);
        String nextCursor = null;
        if (payments.size() == clampedLimit) {
            Payment last = payments.get(payments.size() - 1);
            nextCursor = CursorCodec.encode(last.txid().value(), last.createdAt());
        }
        List<PaymentSummaryResponse> items = payments.stream()
                .map(p -> new PaymentSummaryResponse(
                        p.txid().value(),
                        p.status().name(),
                        p.amount().cents(),
                        BRL,
                        p.createdAt()))
                .toList();
        return ResponseEntity.ok(new PaymentListResponse(items, nextCursor));
    }

    @PostMapping("/{txid}/refunds")
    ResponseEntity<RefundPaymentResponse> refund(
            HttpServletRequest request,
            @AuthenticationPrincipal io.dargent.api.security.ApiKeyPrincipal principal,
            @PathVariable String txid) {
        byte[] rawBody = readRawBody(request);
        String fingerprint = fingerprint(rawBody);
        RefundRequest body = parseRefundBody(rawBody);
        long amountCents = body.validatedAmount();
        String idempotencyKey = idempotencyKey(request);
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);

        RefundPaymentUseCase.Output out = refundUseCase.execute(new RefundPaymentUseCase.Input(
                txid, principal.merchantId(), principal.keyId(), idempotencyKey,
                "POST /v1/payments/" + txid + "/refunds", fingerprint, requestId,
                Money.of(amountCents, BRL)));

        var response = new RefundPaymentResponse(out.id(), out.payment(), out.amount(),
                out.feeReversal(), out.net(), out.status(), out.createdAt());
        ResponseEntity.BodyBuilder builder = org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .location(java.net.URI.create("/v1/payments/" + out.payment() + "/refunds/" + out.id()));
        if (out.replay()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(response);
    }

    // ------------------------------------------------------------ create helpers

    private byte[] readRawBody(HttpServletRequest request) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (java.io.IOException e) {
            throw new RequestValidationException(Map.of("body", "unreadable"));
        }
    }

    private String fingerprint(byte[] rawBody) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(rawBody);
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private CreatePaymentRequest parseBody(byte[] rawBody) {
        if (rawBody.length == 0) {
            throw new RequestValidationException(Map.of("body", "required"));
        }
        try {
            return objectMapper.readValue(rawBody, CreatePaymentRequest.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new RequestValidationException(Map.of("body", "malformed JSON"));
        }
    }

    private RefundRequest parseRefundBody(byte[] rawBody) {
        if (rawBody.length == 0) {
            return new RefundRequest(null);
        }
        try {
            return objectMapper.readValue(rawBody, RefundRequest.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new RequestValidationException(Map.of("body", "malformed JSON"));
        }
    }

    private String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null) {
            throw new RequestValidationException(Map.of("idempotency_key", "is required"));
        }
        if (key.length() < 8 || key.length() > 200) {
            throw new RequestValidationException(Map.of("idempotency_key", "must be 8-200 characters"));
        }
        return key;
    }

    // ---------------------------------------------------------------- body model

    record CreatePaymentRequest(Integer amount, String description, String expiresIn) {

        long validatedAmount() {
            if (amount == null || amount <= 0) {
                throw new RequestValidationException(Map.of("amount", "must be a positive integer"));
            }
            return amount;
        }

        Duration parsedExpiresIn() {
            String raw = expiresIn == null || expiresIn.isBlank() ? "PT30M" : expiresIn;
            Duration d;
            try {
                d = Duration.parse(raw.toUpperCase(Locale.ROOT));
            } catch (java.time.format.DateTimeParseException e) {
                throw new RequestValidationException(Map.of("expiresIn", "must be an ISO-8601 duration"));
            }
            if (d.compareTo(Duration.ofSeconds(30)) < 0 || d.compareTo(Duration.ofHours(24)) > 0) {
                throw new RequestValidationException(Map.of("expiresIn", "must be between 30s and 24h"));
            }
            return d;
        }

        String validatedDescription() {
            if (description == null) {
                return null;
            }
            if (description.length() > 140) {
                throw new RequestValidationException(Map.of("description", "must be at most 140 characters"));
            }
            return description;
        }
    }

    record CreatePaymentResponse(
            String txid,
            String status,
            long amount,
            String currency,
            Instant expiresAt,
            String brcode,
            Duration expiresIn) {}

    record PaymentDetailResponse(
            String txid,
            String status,
            long amount,
            String currency,
            Instant expiresAt,
            String brcode,
            Duration expiresIn) {}

    record PaymentSummaryResponse(
            String txid,
            String status,
            long amount,
            String currency,
            Instant createdAt) {}

    record PaymentListResponse(
            List<PaymentSummaryResponse> items,
            String nextCursor) {}

    // ------------------------------------------------------------ refund body

    record RefundRequest(Integer amount) {
        long validatedAmount() {
            if (amount == null) {
                return -1; // signal: full remaining
            }
            if (amount <= 0) {
                throw new RequestValidationException(Map.of("amount", "must be a positive integer"));
            }
            return amount;
        }
    }

    record RefundPaymentResponse(
            String id,
            String payment,
            long amount,
            long feeReversal,
            long net,
            String status,
            Instant createdAt) {}
}

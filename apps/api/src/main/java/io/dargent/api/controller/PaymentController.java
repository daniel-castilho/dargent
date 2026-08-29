package io.dargent.api.controller;

import io.dargent.api.web.CursorCodec;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PaymentQueryPort;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment read endpoints (E3 spec §5.2, §5.3).
 * Authenticated via API key (SecurityConfig); tenant derived from principal.
 */
@RestController
class PaymentController {

    private final PaymentQueryPort queryPort;

    PaymentController(PaymentQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @GetMapping("/v1/payments/{txid}")
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
                "BRL",
                p.expiresAt(),
                io.dargent.payments.domain.br.BrCode.of(
                        "dargent-dev-receber@example.com",
                        "Dargent Dev LTDA",
                        "SAO PAULO",
                        p.amount().cents(),
                        p.txid()),
                java.time.Duration.between(java.time.Instant.now(), p.expiresAt())));
    }

    @GetMapping("/v1/payments")
    ResponseEntity<PaymentListResponse> list(
            @AuthenticationPrincipal io.dargent.api.security.ApiKeyPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        int clampedLimit = Math.min(Math.max(limit, 1), 100);
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorCodec.decode(cursor); // validate cursor format
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(new PaymentListResponse(List.of(), null));
            }
        }
        List<Payment> payments = queryPort.findPage(principal.merchantId(), cursor, clampedLimit);
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
                        "BRL",
                        p.createdAt()))
                .toList();
        return ResponseEntity.ok(new PaymentListResponse(items, nextCursor));
    }

    record PaymentDetailResponse(
            String txid,
            String status,
            long amount,
            String currency,
            java.time.Instant expiresAt,
            String brcode,
            Duration expiresIn) {}

    record PaymentSummaryResponse(
            String txid,
            String status,
            long amount,
            String currency,
            java.time.Instant createdAt) {}

    record PaymentListResponse(
            List<PaymentSummaryResponse> items,
            String nextCursor) {}
}
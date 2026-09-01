package io.dargent.api.controller;

import io.dargent.api.error.RequestValidationException;
import io.dargent.api.security.ApiKeyPrincipal;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import io.dargent.ledger.application.SettlementUseCase;
import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.port.out.LedgerStore.ProofResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger HTTP surface (E7 spec §5.4–§5.6): balance read, proof diagnostic, rebuild, and settlement.
 * Authenticated via API key (SecurityConfig); the tenant/merchant comes from the principal (AGENTS §3.7),
 * never from path, query or body. All routes are declared explicitly in SecurityConfig (AGENTS §4.1).
 */
@RestController
@RequestMapping("/v1/ledger")
class LedgerController {

    private final SettlementUseCase settlementUseCase;
    private final LedgerReconciliationUseCase reconciliation;

    LedgerController(SettlementUseCase settlementUseCase, LedgerReconciliationUseCase reconciliation) {
        this.settlementUseCase = settlementUseCase;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/accounts/{account}/balance")
    ResponseEntity<BalanceResponse> balance(@PathVariable String account) {
        Account a = reconciliation.balance(account);
        return ResponseEntity.ok(new BalanceResponse(a.account(), a.balanceCents(), a.updatedAt()));
    }

    @GetMapping("/proof")
    ResponseEntity<ProofResponse> proof() {
        ProofResult r = reconciliation.proof();
        return ResponseEntity.ok(new ProofResponse(r.ok(), r.firstDivergence(),
                r.accountsChecked(), r.entriesChecked(), r.postingsChecked()));
    }

    @PostMapping("/rebuild")
    ResponseEntity<ProofResponse> rebuild(@AuthenticationPrincipal ApiKeyPrincipal principal) {
        ProofResult r = reconciliation.rebuild(principal.keyId());
        return ResponseEntity.ok(new ProofResponse(r.ok(), r.firstDivergence(),
                r.accountsChecked(), r.entriesChecked(), r.postingsChecked()));
    }

    @PostMapping("/settlements")
    ResponseEntity<SettlementResponse> settle(HttpServletRequest request,
            @AuthenticationPrincipal ApiKeyPrincipal principal) {
        String idempotencyKey = idempotencyKey(request);
        SettlementUseCase.SettlementResult result =
                settlementUseCase.settle(principal.merchantId(), idempotencyKey, principal.keyId());
        var body = new SettlementResponse(
                result.settlement().id(),
                result.settlement().merchantId(),
                result.settlement().amountCents(),
                result.settlement().settledAt());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replay()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(body);
    }

    private String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null) {
            throw new RequestValidationException(Map.of("idempotency_key", "is required"));
        }
        if (key.length() < 8 || key.length() > 64) {
            throw new RequestValidationException(Map.of("idempotency_key", "must be 8-64 characters"));
        }
        return key;
    }

    record BalanceResponse(String account, long balanceCents, Instant updatedAt) {}

    record ProofResponse(boolean ok, String firstDivergence,
            long accountsChecked, long entriesChecked, long postingsChecked) {}

    record SettlementResponse(UUID id, UUID merchantId, long amountCents, Instant settledAt) {}
}

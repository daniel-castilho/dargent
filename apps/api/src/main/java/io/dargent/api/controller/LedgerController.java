package io.dargent.api.controller;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import io.dargent.api.error.RequestValidationException;
import io.dargent.api.security.ApiKeyAuthenticationFilter;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.security.ApiKeyPrincipal;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import io.dargent.ledger.application.SettlementUseCase;
import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.port.out.LedgerStore.ProofResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>E13 R3: rebuild, proof and settlements are admin-gated by {@code DARGENT_LEDGER_ADMIN_KEY}
 * (E9 Q11 ladder): unset → 404-hidden; invalid/unknown/revoked key → 401 (filter); valid active key
 * that is not the designated admin → 403 fail-closed; the designated admin key → 200, audited
 * {@code ledger_admin_*} with the presented key's real identity (never a sentinel). Balance stays
 * a plain authenticated merchant read.
 */
@RestController
@RequestMapping("/v1/ledger")
class LedgerController {

    private static final Logger log = LoggerFactory.getLogger(LedgerController.class);

    private final SettlementUseCase settlementUseCase;
    private final LedgerReconciliationUseCase reconciliation;
    private final ErrorResponseWriter errorWriter;
    private final String adminKey;

    LedgerController(
            SettlementUseCase settlementUseCase,
            LedgerReconciliationUseCase reconciliation,
            ErrorResponseWriter errorWriter,
            @Value("${DARGENT_LEDGER_ADMIN_KEY:}") String adminKey) {
        this.settlementUseCase = settlementUseCase;
        this.reconciliation = reconciliation;
        this.errorWriter = errorWriter;
        this.adminKey = adminKey;
    }

    @GetMapping("/accounts/{account}/balance")
    ResponseEntity<BalanceResponse> balance(@PathVariable String account) {
        Account a = reconciliation.balance(account);
        return ResponseEntity.ok(new BalanceResponse(a.account(), a.balanceCents(), a.updatedAt()));
    }

    @GetMapping("/proof")
    void proof(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal ApiKeyPrincipal principal)
            throws IOException {
        if (!isAdmin(request, response, principal)) {
            return;
        }
        ProofResult r = reconciliation.proof(principal.keyId());
        writeProof(response, r);
    }

    @PostMapping("/rebuild")
    void rebuild(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal ApiKeyPrincipal principal)
            throws IOException {
        if (!isAdmin(request, response, principal)) {
            return;
        }
        ProofResult r = reconciliation.rebuild(principal.keyId());
        writeProof(response, r);
    }

    @PostMapping("/settlements")
    void settle(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal ApiKeyPrincipal principal)
            throws IOException {
        if (!isAdmin(request, response, principal)) {
            return;
        }
        String idempotencyKey = idempotencyKey(request);
        SettlementUseCase.SettlementResult result =
                settlementUseCase.settle(principal.merchantId(), idempotencyKey, principal.keyId());
        response.setStatus(HttpStatus.CREATED.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        StringBuilder body = new StringBuilder()
                .append("{\"id\":\"")
                .append(result.settlement().id())
                .append("\",\"merchantId\":\"")
                .append(result.settlement().merchantId())
                .append("\",\"amountCents\":")
                .append(result.settlement().amountCents())
                .append(",\"settledAt\":\"")
                .append(result.settlement().settledAt())
                .append("\"}");
        if (result.replay()) {
            response.setHeader("Idempotent-Replay", "true");
        }
        response.getWriter().write(body.toString());
    }

    /**
     * E13 R3 admin gate (E9 Q11 ladder, mirrors {@code OutboxAdminController.isAdmin}): when
     * {@code DARGENT_LEDGER_ADMIN_KEY} is unset/blank the route is 404-hidden; otherwise the caller
     * must present that exact key (constant-time compare). A valid API key that is not the admin
     * key is 403 fail-closed — the env match never bypasses validation (validation happens first,
     * in the filter, for every request).
     */
    private boolean isAdmin(HttpServletRequest request, HttpServletResponse response, ApiKeyPrincipal principal)
            throws IOException {
        if (adminKey == null || adminKey.isBlank()) {
            errorWriter.write(request, response, ErrorCode.NOT_FOUND, "Unknown route", (Map<String, String>) null);
            return false;
        }
        String rawKey = (String) request.getAttribute(ApiKeyAuthenticationFilter.RAW_KEY_ATTRIBUTE);
        if (rawKey == null
                || !ApiKeyHasher.constantTimeEquals(ApiKeyHasher.hash(adminKey), ApiKeyHasher.hash(rawKey))) {
            log.warn("forbidden ledger admin action by key {} merchant {}", principal.keyId(), principal.merchantId());
            errorWriter.write(request, response, ErrorCode.FORBIDDEN, "Forbidden", (Map<String, String>) null);
            return false;
        }
        return true;
    }

    private void writeProof(HttpServletResponse response, ProofResult r) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write("{\"ok\":"
                        + r.ok()
                        + ",\"firstDivergence\":"
                        + jsonString(r.firstDivergence())
                        + ",\"accountsChecked\":"
                        + r.accountsChecked()
                        + ",\"entriesChecked\":"
                        + r.entriesChecked()
                        + ",\"postingsChecked\":"
                        + r.postingsChecked()
                        + "}");
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
}

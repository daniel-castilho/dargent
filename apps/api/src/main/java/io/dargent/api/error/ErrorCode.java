package io.dargent.api.error;

import org.springframework.http.HttpStatus;

/**
 * The canonical error catalog (design.md §6.3, E3 spec §5.4). Clients branch on {@code code},
 * never on {@code detail}. Webhook (invalid_signature/signature_expired) and refund
 * (payment_not_refundable/refund_exceeds_remaining) codes are dormant until E4/E8, but live here
 * already so every path emits through the single writer from day one.
 */
public enum ErrorCode {

    INVALID_REQUEST("invalid_request", HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Unauthorized"),
    INVALID_SIGNATURE("invalid_signature", HttpStatus.UNAUTHORIZED, "Invalid signature"),
    SIGNATURE_EXPIRED("signature_expired", HttpStatus.UNAUTHORIZED, "Signature expired"),
    NOT_FOUND("not_found", HttpStatus.NOT_FOUND, "Resource not found"),
    PAYMENT_NOT_FOUND("payment_not_found", HttpStatus.NOT_FOUND, "Payment not found"),
    LEDGER_ACCOUNT_NOT_FOUND("account_not_found", HttpStatus.NOT_FOUND, "Ledger account not found"),
    NO_BALANCE_TO_SETTLE("no_balance_to_settle", HttpStatus.CONFLICT, "No balance to settle"),
    IDEMPOTENCY_KEY_CONFLICT("idempotency_key_conflict", HttpStatus.CONFLICT, "Idempotency key conflict"),
    PAYMENT_NOT_REFUNDABLE("payment_not_refundable", HttpStatus.CONFLICT, "Payment not refundable"),
    REFUND_EXCEEDS_REMAINING("refund_exceeds_remaining", HttpStatus.CONFLICT, "Refund exceeds remaining amount"),
    INSUFFICIENT_MERCHANT_BALANCE("insufficient_merchant_balance", HttpStatus.CONFLICT, "Insufficient merchant balance"),
    BALANCE_UNAVAILABLE("balance_unavailable", HttpStatus.CONFLICT, "Balance service unavailable"),
    INVALID_TRANSITION("invalid_transition", HttpStatus.CONFLICT, "Invalid state transition"),
    INVALID_STATE("invalid_state", HttpStatus.CONFLICT, "Invalid state"),
    IDEMPOTENCY_KEY_IN_FLIGHT("idempotency_key_in_flight", HttpStatus.TOO_EARLY, "Idempotency key in flight"),
    PSP_UNAVAILABLE("psp_unavailable", HttpStatus.BAD_GATEWAY, "Payment provider unavailable"),
    INTERNAL("internal", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final String code;
    private final HttpStatus httpStatus;
    private final String title;

    ErrorCode(String code, HttpStatus httpStatus, String title) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }
}

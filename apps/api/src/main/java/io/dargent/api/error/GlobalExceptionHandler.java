package io.dargent.api.error;

import io.dargent.payments.application.IdempotencyKeyConflictException;
import io.dargent.payments.application.IdempotencyKeyInFlightException;
import io.dargent.payments.application.PspUnavailableException;
import io.dargent.payments.application.RefundPaymentUseCase;
import io.dargent.payments.domain.exception.InvalidTransitionException;
import io.dargent.payments.domain.exception.RefundExceedsRemainingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single error facility (design.md §6.3, E3 spec §5.4): every exception maps through
 * {@link ErrorResponseWriter} — one writer, no per-handler formats. Domain payment exceptions
 * currently arrive as {@link InvalidTransitionException}; the remaining catalog codes are mapped
 * by their dedicated handlers/use cases (E4/E5) and are dormant here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ErrorResponseWriter writer;

    public GlobalExceptionHandler(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void validation(HttpServletRequest request, HttpServletResponse response,
            MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        writer.write(request, response, ErrorCode.INVALID_REQUEST, "Validation failed", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void unreadable(HttpServletRequest request, HttpServletResponse response,
            HttpMessageNotReadableException e) {
        writer.write(request, response, ErrorCode.INVALID_REQUEST, "Malformed JSON body",
                Map.of("body", "unreadable"));
    }

    @ExceptionHandler(RequestValidationException.class)
    public void requestValidation(HttpServletRequest request, HttpServletResponse response,
            RequestValidationException e) {
        writer.write(request, response, ErrorCode.INVALID_REQUEST, "Validation failed", e.fields());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public void invalidTransition(HttpServletRequest request, HttpServletResponse response,
            InvalidTransitionException e) {
        writer.write(request, response, ErrorCode.INVALID_TRANSITION, e.getMessage());
    }

    @ExceptionHandler(io.dargent.ledger.application.NoBalanceToSettleException.class)
    public void noBalanceToSettle(HttpServletRequest request, HttpServletResponse response,
            io.dargent.ledger.application.NoBalanceToSettleException e) {
        writer.write(request, response, ErrorCode.NO_BALANCE_TO_SETTLE, e.getMessage());
    }

    @ExceptionHandler(io.dargent.ledger.application.LedgerAccountNotFoundException.class)
    public void ledgerAccountNotFound(HttpServletRequest request, HttpServletResponse response,
            io.dargent.ledger.application.LedgerAccountNotFoundException e) {
        writer.write(request, response, ErrorCode.LEDGER_ACCOUNT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void notFound(HttpServletRequest request, HttpServletResponse response, NoResourceFoundException e) {
        writer.write(request, response, ErrorCode.NOT_FOUND, "Unknown route");
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public void idempotencyConflict(HttpServletRequest request, HttpServletResponse response,
            IdempotencyKeyConflictException e) {
        writer.write(request, response, ErrorCode.IDEMPOTENCY_KEY_CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInFlightException.class)
    public void idempotencyInFlight(HttpServletRequest request, HttpServletResponse response,
            IdempotencyKeyInFlightException e) {
        response.setHeader("Retry-After", "1");
        writer.write(request, response, ErrorCode.IDEMPOTENCY_KEY_IN_FLIGHT, e.getMessage());
    }

    @ExceptionHandler(PspUnavailableException.class)
    public void pspUnavailable(HttpServletRequest request, HttpServletResponse response,
            PspUnavailableException e) {
        writer.write(request, response, ErrorCode.PSP_UNAVAILABLE, "Payment provider unavailable", e);
    }

    @ExceptionHandler(RefundPaymentUseCase.PaymentNotFoundException.class)
    public void paymentNotFound(HttpServletRequest request, HttpServletResponse response,
            RefundPaymentUseCase.PaymentNotFoundException e) {
        writer.write(request, response, ErrorCode.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RefundPaymentUseCase.InvalidStateException.class)
    public void invalidState(HttpServletRequest request, HttpServletResponse response,
            RefundPaymentUseCase.InvalidStateException e) {
        writer.write(request, response, ErrorCode.INVALID_STATE, e.getMessage());
    }

    @ExceptionHandler(RefundExceedsRemainingException.class)
    public void refundExceedsRemaining(HttpServletRequest request, HttpServletResponse response,
            RefundExceedsRemainingException e) {
        writer.write(request, response, ErrorCode.REFUND_EXCEEDS_REMAINING, e.getMessage());
    }

    @ExceptionHandler(RefundPaymentUseCase.InsufficientMerchantBalanceException.class)
    public void insufficientMerchantBalance(HttpServletRequest request, HttpServletResponse response,
            RefundPaymentUseCase.InsufficientMerchantBalanceException e) {
        writer.write(request, response, ErrorCode.INSUFFICIENT_MERCHANT_BALANCE, e.getMessage());
    }

    @ExceptionHandler(RefundPaymentUseCase.BalanceUnavailableException.class)
    public void balanceUnavailable(HttpServletRequest request, HttpServletResponse response,
            RefundPaymentUseCase.BalanceUnavailableException e) {
        writer.write(request, response, ErrorCode.BALANCE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(RefundPaymentUseCase.OptimisticLockException.class)
    public void optimisticLock(HttpServletRequest request, HttpServletResponse response,
            RefundPaymentUseCase.OptimisticLockException e) {
        writer.write(request, response, ErrorCode.INVALID_STATE, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void fallback(HttpServletRequest request, HttpServletResponse response, Exception e) {
        writer.write(request, response, ErrorCode.INTERNAL, null, null, e);
    }
}

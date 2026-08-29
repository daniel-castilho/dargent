package io.dargent.api.error;

import io.dargent.payments.domain.exception.InvalidTransitionException;
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

    @ExceptionHandler(InvalidTransitionException.class)
    public void invalidTransition(HttpServletRequest request, HttpServletResponse response,
            InvalidTransitionException e) {
        writer.write(request, response, ErrorCode.INVALID_TRANSITION, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void notFound(HttpServletRequest request, HttpServletResponse response, NoResourceFoundException e) {
        writer.write(request, response, ErrorCode.NOT_FOUND, "Unknown route");
    }

    @ExceptionHandler(Exception.class)
    public void fallback(HttpServletRequest request, HttpServletResponse response, Exception e) {
        writer.write(request, response, ErrorCode.INTERNAL, null, null, e);
    }
}

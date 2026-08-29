package io.dargent.pspsimulator.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single error emitter for the simulator (analogous to the platform's {@code ErrorResponseWriter},
 * deliberately independent). Domain violations arrive as {@link PspApiException} carrying their own
 * status+code; everything unreadable/infected arrives here as a canonical envelope.
 */
@RestControllerAdvice
public class PspExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PspExceptionHandler.class);

    @ExceptionHandler(PspApiException.class)
    public ResponseEntity<ErrorResponse> pspApi(PspApiException e) {
        return ResponseEntity.status(e.getHttpStatus()).body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", "Malformed JSON body"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", "Unknown route"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> fallback(Exception e) {
        log.warn("unhandled simulator error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal", "Unexpected simulator error"));
    }
}
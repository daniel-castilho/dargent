package io.dargent.pspsimulator.error;

/**
 * The simulator's own error payload type (E2 spec §5.3 — {@code {code, message}} shape; no
 * problem+json here). Deliberately framework-free so the {@code Charge} domain rules can throw it
 * without knowing Spring exists.
 */
public final class PspApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public PspApiException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
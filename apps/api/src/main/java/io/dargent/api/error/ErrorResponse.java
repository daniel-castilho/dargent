package io.dargent.api.error;

import java.util.Map;

/**
 * RFC 9457 {@code application/problem+json} body emitted by the single
 * {@link ErrorResponseWriter} (E3 spec §5.4). Machines branch on {@code code}; {@code detail} is
 * safe to expose; {@code fields} (field → message) is present only for validation errors. Jackson 3
 * serializes this record with {@code @JsonProperty} stable names — no per-writer formats.
 */
public record ErrorResponse(
        String type,
        String title,
        int status,
        String code,
        String detail,
        Map<String, String> fields) {

    public static ErrorResponse of(ErrorCode errorCode, String detail) {
        return new ErrorResponse("about:blank", errorCode.title(), errorCode.httpStatus().value(),
                errorCode.code(), detail, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String detail, Map<String, String> fields) {
        return new ErrorResponse("about:blank", errorCode.title(), errorCode.httpStatus().value(),
                errorCode.code(), detail, fields);
    }
}

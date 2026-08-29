package io.dargent.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The single emitter of every error response (design.md §6.3, E3 spec §5.4). Global advice and the
 * security filters (API-key, future HMAC) all route through this writer so no per-filter format
 * drifts in. A {@code 500 internal} logs method + URI + exception and never leaks the internal
 * message to the client — {@code detail} stays a fixed safe string. Jackson 3 writes the body.
 */
@Component
public class ErrorResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponseWriter.class);
    private static final String SAFE_INTERNAL_DETAIL = "An unexpected error occurred. Reference request id in logs.";

    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String detail) {
        write(request, response, errorCode, detail, null, null);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String detail,
            Map<String, String> fields) {
        write(request, response, errorCode, detail, fields, null);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String detail,
            Throwable cause) {
        write(request, response, errorCode, detail, null, cause);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String detail,
            Map<String, String> fields, Throwable cause) {
        String safeDetail = errorCode == ErrorCode.INTERNAL ? SAFE_INTERNAL_DETAIL : detail;
        if (errorCode == ErrorCode.INTERNAL) {
            if (cause != null) {
                log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), cause);
            } else {
                log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI());
            }
        }
        writeBody(response, errorCode, safeDetail, fields);
    }

    private void writeBody(HttpServletResponse response, ErrorCode errorCode, String detail,
            Map<String, String> fields) {
        ErrorResponse body = ErrorResponse.of(errorCode, detail, fields);
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (IOException e) {
            log.error("failed to serialize error response", e);
        }
    }
}
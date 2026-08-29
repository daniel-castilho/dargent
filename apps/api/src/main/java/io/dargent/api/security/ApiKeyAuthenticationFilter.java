package io.dargent.api.security;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stripe-style API key authentication (E3 spec §3.2, §5.9, design.md §8.1):
 * {@code Authorization: Bearer psp_test_...} → lookup by prefix → constant-time hash compare →
 * set {@link ApiKeyPrincipal} in SecurityContext. Invalid/missing key → 401 via
 * {@link ErrorResponseWriter} (single error emitter).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    private final ApiKeyRepository repository;
    private final ErrorResponseWriter errorWriter;

    public ApiKeyAuthenticationFilter(ApiKeyRepository repository, ErrorResponseWriter errorWriter) {
        this.repository = repository;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String auth = request.getHeader(AUTH_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Missing or invalid Authorization header", (Map<String, String>) null);
            return;
        }
        String rawKey = auth.substring(BEARER_PREFIX.length()).trim();
        if (rawKey.isEmpty()) {
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Empty API key", (Map<String, String>) null);
            return;
        }

        String prefix;
        try {
            prefix = ApiKeyHasher.prefix(rawKey);
        } catch (IllegalArgumentException e) {
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Malformed API key", (Map<String, String>) null);
            return;
        }

        var keyRecordOpt = repository.findByPrefix(prefix);
        if (keyRecordOpt.isEmpty()) {
            log.debug("API key not found for prefix {}", prefix);
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Invalid API key", (Map<String, String>) null);
            return;
        }

        var keyRecord = keyRecordOpt.get();
        if (!ApiKeyHasher.constantTimeEquals(keyRecord.keyHash(), ApiKeyHasher.hash(rawKey))) {
            log.debug("API key hash mismatch for prefix {}", prefix);
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Invalid API key", (Map<String, String>) null);
            return;
        }

        if (keyRecord.revokedAt() != null) {
            log.debug("API key revoked: {}", keyRecord.id());
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "API key revoked", (Map<String, String>) null);
            return;
        }

        ApiKeyPrincipal principal = new ApiKeyPrincipal(keyRecord.merchantId(), keyRecord.id());
        var authToken = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authToken);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
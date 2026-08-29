package io.dargent.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code X-Request-Id} correlation (E3 spec §5.4): an accepted id is validated against
 * {@code [A-Za-z0-9-]{8,64}} — an invalid id is <em>never</em> rejected, it is replaced with a
 * generated UUID; an absent id is generated. The final id is echoed on every response and exposed to
 * the request/response/MDC so the use case and adapters can carry it into the outbox envelope's
 * {@code requestId}.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !VALID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        try {
            MDC.put("requestId", requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}

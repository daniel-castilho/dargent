package io.dargent.api.web;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Webhook abuse controls (E15 S1 — DEBT-8 real closure, owner adjudication 2026-09-08): in-app
 * 413 body cap + 429 rate limit on {@code POST /webhooks/psp} — the only permitAll route, so a
 * flood is otherwise bounded by nothing (E13 threat model surface 1).
 *
 * <p>Control order, carved by ITs: (1) oversize → {@code 413} BEFORE the HMAC is consumed — the
 * {@code Content-Length} header is checked first (zero body read) and a bounded read guards
 * chunked/lying headers; (2) over-limit → {@code 429} with ZERO side effects — nothing is
 * persisted, no use case invoked; a flood never amplifies into a write flood; (3) valid traffic
 * under limit → byte-identical to the pre-S1 flow (the controller reads the cached body).
 *
 * <p>Rate-limit scope key: first hop of {@code X-Forwarded-For} (NGINX edge sets it) falling back
 * to {@code getRemoteAddr()} (direct/test traffic). Both rejection reasons count in the frozen
 * {@code dargent.webhook.rejections} series feeding the S2 alert rule (observability.md §3).
 */
public final class WebhookAbuseControlFilter extends OncePerRequestFilter {

    /** Request attribute carrying the bounded-read body consumed by the controller. */
    public static final String CACHED_BODY_ATTRIBUTE = WebhookAbuseControlFilter.class.getName() + ".cachedBody";

    static final String REJECTIONS_METRIC = "dargent.webhook.rejections";

    private static final Logger log = LoggerFactory.getLogger(WebhookAbuseControlFilter.class);

    private final WebhookRateLimiter rateLimiter;
    private final ErrorResponseWriter errorWriter;
    private final MeterRegistry metrics;
    private final long bodyCapBytes;

    public WebhookAbuseControlFilter(
            WebhookRateLimiter rateLimiter, ErrorResponseWriter errorWriter, MeterRegistry metrics, long bodyCapBytes) {
        this.rateLimiter = rateLimiter;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
        this.bodyCapBytes = bodyCapBytes;
        // Pre-register both frozen reasons (E12 N8 convention): the series must be present at 0
        // on a healthy system so the S2 rule can always evaluate (presence is the signal).
        metrics.counter(REJECTIONS_METRIC, "reason", "rate_limited");
        metrics.counter(REJECTIONS_METRIC, "reason", "body_too_large");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the public webhook route: abuse controls never touch authenticated API paths.
        return !("POST".equals(request.getMethod()) && "/webhooks/psp".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // ---- 413 fast path: honest Content-Length — no body byte is read.
        if (request.getContentLengthLong() > bodyCapBytes) {
            reject(request, response, ErrorCode.PAYLOAD_TOO_LARGE, "body_too_large");
            return;
        }

        // ---- 429: rate limit — decided before the body is buffered; zero side effects on reject.
        if (!rateLimiter.tryConsume(clientKey(request))) {
            reject(request, response, ErrorCode.RATE_LIMITED, "rate_limited");
            return;
        }

        // ---- 413 bounded read: chunked bodies or lying Content-Length are capped at cap+1 —
        // one byte over proves oversize without ever buffering an unbounded stream in memory.
        InputStream in = request.getInputStream();
        byte[] body = in.readNBytes((int) Math.min(bodyCapBytes + 1, Integer.MAX_VALUE));
        if (body.length > bodyCapBytes || in.read() != -1) {
            reject(request, response, ErrorCode.PAYLOAD_TOO_LARGE, "body_too_large");
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String reason)
            throws IOException {
        metrics.counter(REJECTIONS_METRIC, "reason", reason).increment();
        // WARN per the level contract: degraded-but-self-healing — the control did its job.
        log.warn("Webhook rejected: reason={} client={} uri={}", reason, clientKey(request), request.getRequestURI());
        errorWriter.write(
                request,
                response,
                code,
                "body_too_large".equals(reason)
                        ? "Webhook body exceeds " + bodyCapBytes + " bytes"
                        : "Webhook request rate exceeded");
    }

    static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String firstHop = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }
        return request.getRemoteAddr();
    }

    /** Serves the bounded-read body to the controller without re-reading the stream. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("non-async stream");
                }

                @Override
                public int read() {
                    return buffer.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return buffer.read(b, off, len);
                }
            };
        }
    }
}

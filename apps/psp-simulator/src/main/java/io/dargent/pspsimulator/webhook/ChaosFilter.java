package io.dargent.pspsimulator.webhook;

import java.io.IOException;
import java.util.Random;

import io.dargent.pspsimulator.config.ChaosProperties;
import io.dargent.pspsimulator.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Request-side chaos knobs (spec §6): {@code psp-error-rate} (503 {@code psp_unavailable}) and
 * {@code psp-latency-ms} (the ONE sanctioned production {@code Thread.sleep}, capped at 30 000).
 * Applied only to {@code /cobs/**} — actuator health and anything else passes untouched, so the
 * compose healthcheck can never be squashed. Interaction order per spec: latency → error-rate →
 * endpoint handler. Forced extreme rates make tests deterministic without any seed reliance.
 */
@Component
public class ChaosFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChaosFilter.class);

    private final ChaosProperties chaos;
    private final Random random;
    private final ObjectMapper mapper;

    public ChaosFilter(ChaosProperties chaos, Random random, ObjectMapper mapper) {
        this.chaos = chaos;
        this.random = random;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        int latencyMs = chaos.getPspLatencyMs();
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (chaos.getPspErrorRate() > 0.0 && random.nextDouble() < chaos.getPspErrorRate()) {
            log.debug("chaos error-rate squashes {} as 503 psp_unavailable", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(mapper.writeValueAsString(
                    new ErrorResponse("psp_unavailable", "PSP simulator unavailable (chaos error-rate)")));
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/cobs");
    }
}
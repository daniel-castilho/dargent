package io.dargent.api.config;

import io.dargent.api.web.WebhookAbuseControlFilter;
import io.dargent.api.web.WebhookRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook abuse-control wiring (E15 S1): the filter registration + the shared limiter bean.
 * Registration order is the earliest practical position so an oversized/over-limit request never
 * reaches the security chain, let alone the application (413 before HMAC, 429 with zero side
 * effects — control order proven by {@code WebhookRateLimitIT}/{@code WebhookBodyCapIT}).
 *
 * <p>Defaults are deliberately generous (capacity 100, refill 0.5/s, cap 64 KiB) so demo/smoke
 * paths never trip; ITs tune them explicitly per test (spec §4: safe defaults, tests tune).
 */
@Configuration
public class WebhookAbuseControlConfig {

    @Bean
    public WebhookRateLimiter webhookRateLimiter(
            Clock clock,
            @Value("${dargent.webhook.rate-limit.capacity:100}") long capacity,
            @Value("${dargent.webhook.rate-limit.refill-per-second:0.5}") double refillPerSecond) {
        return new WebhookRateLimiter(clock, capacity, refillPerSecond);
    }

    @Bean
    public FilterRegistrationBean<WebhookAbuseControlFilter> webhookAbuseControlFilter(
            WebhookRateLimiter rateLimiter,
            io.dargent.api.error.ErrorResponseWriter errorWriter,
            MeterRegistry metrics,
            @Value("${dargent.webhook.body-cap-bytes:65536}") long bodyCapBytes) {
        FilterRegistrationBean<WebhookAbuseControlFilter> registration = new FilterRegistrationBean<>(
                new WebhookAbuseControlFilter(rateLimiter, errorWriter, metrics, bodyCapBytes));
        registration.addUrlPatterns("/webhooks/*");
        // Earliest practical position: abuse verdicts precede the security chain and the app.
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }
}

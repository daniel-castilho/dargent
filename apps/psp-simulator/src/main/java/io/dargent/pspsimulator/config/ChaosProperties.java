package io.dargent.pspsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The five chaos knobs (E2 spec §3.2/§6) plus the seedable-randomness seed. All default OFF so the
 * M0 compose contract ("all chaos off") holds untouched. Bounds are enforced at binding time:
 * delays/latencies are non-negative and capped at 30 000 ms, rates are clamped into [0, 1].
 *
 * <p>Interaction order when a knob is active: {@code latency} → {@code error-rate} → endpoint handler
 * → webhook dispatch ({@code delay} → {@code drop} → {@code duplicate} per delivery) — spec §6.
 */
@ConfigurationProperties("dargent.psp.chaos")
public class ChaosProperties {

    private boolean webhookDuplicate;
    private int webhookDelayMs;
    private double webhookDropRate;
    private double pspErrorRate;
    private int pspLatencyMs;
    private Long seed;

    public boolean isWebhookDuplicate() {
        return webhookDuplicate;
    }

    public void setWebhookDuplicate(boolean webhookDuplicate) {
        this.webhookDuplicate = webhookDuplicate;
    }

    /** Dispatch delay in ms — delivery is scheduled, not slept; cap 30 000. */
    public int getWebhookDelayMs() {
        return webhookDelayMs;
    }

    public void setWebhookDelayMs(int webhookDelayMs) {
        this.webhookDelayMs = cap(webhookDelayMs);
    }

    /** Per-delivery discard probability, driven by the seedable {@link java.util.Random}. */
    public double getWebhookDropRate() {
        return webhookDropRate;
    }

    public void setWebhookDropRate(double webhookDropRate) {
        this.webhookDropRate = clampRate(webhookDropRate);
    }

    /** Per-endpoint-call 503 {@code psp_unavailable} probability. */
    public double getPspErrorRate() {
        return pspErrorRate;
    }

    public void setPspErrorRate(double pspErrorRate) {
        this.pspErrorRate = clampRate(pspErrorRate);
    }

    /** Request handling delay in ms (the one sanctioned production {@code Thread.sleep}); cap 30 000. */
    public int getPspLatencyMs() {
        return pspLatencyMs;
    }

    public void setPspLatencyMs(int pspLatencyMs) {
        this.pspLatencyMs = cap(pspLatencyMs);
    }

    /** Randomness seed for the probabilistic knobs; null means "system-random". */
    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    private static int cap(int value) {
        return Math.min(Math.max(value, 0), 30_000);
    }

    private static double clampRate(double value) {
        return Math.min(Math.max(value, 0.0), 1.0);
    }
}
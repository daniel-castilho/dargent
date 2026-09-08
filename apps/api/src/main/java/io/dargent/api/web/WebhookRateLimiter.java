package io.dargent.api.web;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hand-rolled token bucket (E15 S1, DEBT-8 real closure — zero-dependency by design; bucket4j was
 * rejected in Q-batch adjudication 2026-09-08). Per-key capacity + refill rate; refill is computed
 * lazily from the injected {@link Clock} so tests advance time instead of sleeping (AGENTS §5.3).
 *
 * <p>State is one {@link Bucket} per distinct client key in a {@link ConcurrentHashMap}; per-bucket
 * refill+consume is atomic under the bucket's monitor, so concurrent bursts never over-admit.
 */
public final class WebhookRateLimiter {

    private final Clock clock;
    private final long capacity;
    private final double refillPerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public WebhookRateLimiter(Clock clock, long capacity, double refillPerSecond) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be > 0");
        }
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    /**
     * Attempts to consume one token for {@code key}. {@code true} = allowed. A fresh bucket starts
     * full; consumption happens only in {@code tryConsume} (one token per admission, exactly).
     */
    public boolean tryConsume(String key) {
        long now = clock.instant().toEpochMilli();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        return bucket.tryConsume(now, refillPerSecond, capacity);
    }

    /** Drops all state (test seam — recovery-window ITs reset between assertions). */
    public void reset() {
        buckets.clear();
    }

    private static final class Bucket {
        private long tokens; // guarded by this
        private long lastRefillMilli; // guarded by this

        Bucket(long capacity, long nowMilli) {
            this.tokens = capacity;
            this.lastRefillMilli = nowMilli;
        }

        synchronized boolean tryConsume(long nowMilli, double perSecond, long capacity) {
            long elapsed = nowMilli - lastRefillMilli;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (long) Math.floor(elapsed / 1000.0 * perSecond));
                lastRefillMilli = nowMilli;
            }
            if (tokens >= 1) {
                tokens--;
                return true;
            }
            return false;
        }
    }
}

package io.dargent.payments.adapter.out.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link ReplayCacheClient} (M5 S2, D3). Values are opaque JSON strings — the codec
 * lives in {@link CachedIdempotencyStore} so replay decode logic is unit-testable without Redis.
 * The client exports no semantics: get/put/delete only. Lettuce connects lazily; a dead cache is
 * absorbed by the caller (fail-open).
 */
public final class RedisReplayCacheClient implements ReplayCacheClient {

    /** Cap the TTL at one day — a replay snapshot is immutable, so expiry only bounds memory. */
    private static final long MAX_TTL_SECONDS = 86_400;

    private final StringRedisTemplate redis;

    public RedisReplayCacheClient(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void put(String key, String json, Duration ttl) {
        long seconds = Math.max(1, Math.min(ttl.toSeconds(), MAX_TTL_SECONDS));
        redis.opsForValue().set(key, json, seconds, TimeUnit.SECONDS);
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }
}

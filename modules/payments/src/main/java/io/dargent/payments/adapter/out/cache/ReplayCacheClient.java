package io.dargent.payments.adapter.out.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache seam for the M5 S2 idempotent-replay cache (D3). A dumb key → opaque-JSON store behind the
 * domain {@code IdempotencyStore} port; implementations hold zero business rules (AGENTS §2.2).
 * Fail-open is the contract: any {@link RuntimeException} raised by a client is absorbed by
 * {@link CachedIdempotencyStore} and the read/write falls back to the database.
 */
public interface ReplayCacheClient {

    Optional<String> get(String key);

    void put(String key, String json, Duration ttl);

    void delete(String key);
}

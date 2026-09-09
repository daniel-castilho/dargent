package io.dargent.payments.adapter.out.cache;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * M5 S2 (D3): the idempotent-replay read cache. Decorates the JDBC {@link IdempotencyStore} with a
 * completed-snapshot cache (TTL-bounded, evicted on delete, read-through on COMPLETED hits/misses).
 *
 * <p>Correctness contract (why this is safe): only COMPLETED rows are ever cached — IN_FLIGHT rows
 * stay DB-only, so 425 arbitration and the {@code INSERT ... ON CONFLICT} race are untouched and the
 * database remains the arbiter (AGENTS §3.2). A COMPLETED {@code idempotency_keys} row is immutable
 * (its snapshot never changes) and is only ever deleted by an owner holding an IN_FLIGHT row — which
 * is never the cached state — so a cached snapshot can never contradict the row. The full record
 * (including {@code requestFingerprint}) is cached, preserving the 409-conflict decision on replays.
 *
 * <p>Fail-open by contract (m5-spec §3): every cache interaction is guarded — on any
 * {@link RuntimeException} the operation falls back to the delegate and is counted in
 * {@code dargent.cache.failopen}. The cache is an optimization, never a dependency of the money path
 * (STOP 3).
 */
public final class CachedIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(CachedIdempotencyStore.class);

    public static final String METRIC_PATH = "idempotency-replay";
    private static final String HITS = "dargent.cache.hits";
    private static final String MISSES = "dargent.cache.misses";
    private static final String FAIL_OPEN = "dargent.cache.failopen";

    private final IdempotencyStore delegate;
    private final ReplayCacheClient cache;
    private final ObjectMapper json;
    private final Duration ttl;
    private final MeterRegistry metrics;

    public CachedIdempotencyStore(
            IdempotencyStore delegate,
            ReplayCacheClient cache,
            ObjectMapper json,
            Duration ttl,
            MeterRegistry metrics) {
        this.delegate = delegate;
        this.cache = cache;
        this.json = json;
        this.ttl = ttl;
        this.metrics = metrics;
    }

    @Override
    public Optional<IdempotencyRecord> insertIfAbsent(
            UUID merchantId, String idempotencyKey, String endpoint, String requestFingerprint) {
        String cacheKey = cacheKey(merchantId, idempotencyKey, endpoint);
        try {
            Optional<IdempotencyRecord> cached = cache.get(cacheKey).map(this::decode);
            if (cached.isPresent()) {
                counter(HITS).increment();
                return cached;
            }
            counter(MISSES).increment();
        } catch (RuntimeException e) {
            counter(FAIL_OPEN).increment();
            log.warn("replay cache read failed for key {} — falling back to DB (fail-open)", idempotencyKey, e);
        }
        Optional<IdempotencyRecord> existing =
                delegate.insertIfAbsent(merchantId, idempotencyKey, endpoint, requestFingerprint);
        // Read-through: a COMPLETED row read from the DB repopulates the cache (new TTL), so a replay
        // storm after a cache flush/expiry never double-reads the DB. IN_FLIGHT rows are never cached.
        existing.filter(r -> "COMPLETED".equals(r.state())).ifPresent(r -> put(cacheKey, r));
        return existing;
    }

    @Override
    public void markCompleted(
            UUID merchantId,
            String idempotencyKey,
            String endpoint,
            Txid paymentTxid,
            int responseStatus,
            Map<String, Object> responseBody,
            String requestFingerprint) {
        delegate.markCompleted(
                merchantId, idempotencyKey, endpoint, paymentTxid, responseStatus, responseBody, requestFingerprint);
        put(
                cacheKey(merchantId, idempotencyKey, endpoint),
                new IdempotencyRecord(
                        merchantId,
                        idempotencyKey,
                        endpoint,
                        requestFingerprint,
                        "COMPLETED",
                        paymentTxid.value(),
                        responseStatus,
                        responseBody));
    }

    @Override
    public void delete(UUID merchantId, String idempotencyKey, String endpoint) {
        delegate.delete(merchantId, idempotencyKey, endpoint);
        String cacheKey = cacheKey(merchantId, idempotencyKey, endpoint);
        try {
            cache.delete(cacheKey);
        } catch (RuntimeException e) {
            counter(FAIL_OPEN).increment();
            log.warn(
                    "replay cache evict failed for key {} — TTL bounds the stale window (fail-open)",
                    idempotencyKey,
                    e);
        }
    }

    private void put(String cacheKey, IdempotencyRecord record) {
        String encoded;
        try {
            encoded = json.writeValueAsString(record);
        } catch (Exception e) {
            counter(FAIL_OPEN).increment();
            log.warn("replay cache encode failed for key {} — DB remains authoritative (fail-open)", cacheKey, e);
            return;
        }
        try {
            cache.put(cacheKey, encoded, ttl);
        } catch (RuntimeException e) {
            counter(FAIL_OPEN).increment();
            log.warn("replay cache write failed for key {} — DB remains authoritative (fail-open)", cacheKey, e);
        }
    }

    private IdempotencyRecord decode(String cachedJson) {
        try {
            return json.readValue(cachedJson, IdempotencyRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode cached idempotency record", e);
        }
    }

    private String cacheKey(UUID merchantId, String idempotencyKey, String endpoint) {
        return "dargent:idempotency-replay:" + merchantId + ":" + endpoint + ":" + idempotencyKey;
    }

    private Counter counter(String name) {
        return metrics.counter(name, "path", METRIC_PATH);
    }
}

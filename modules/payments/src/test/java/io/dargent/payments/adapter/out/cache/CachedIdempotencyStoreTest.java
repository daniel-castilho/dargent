package io.dargent.payments.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M5 S2 (D3) unit contract for the replay cache decorator. The fake {@link ReplayCacheClient}
 * stands in for Redis (the outside world — AGENTS §3.9 permits faking it, never the DB/outbox);
 * the real-Redis fail-open and byte-equal proofs live in {@code ReplayCacheIT} (apps/api).
 */
class CachedIdempotencyStoreTest {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String KEY = "idem-unit-01";
    private static final String ENDPOINT = "POST /v1/payments";
    private static final String FINGERPRINT = "abc123";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Txid TXID = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");

    private static Map<String, Object> snapshotBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txid", TXID.value());
        body.put("status", "PENDING");
        body.put("expiresAt", "2026-08-29T12:02:00Z");
        body.put("brcode", "000201-terribly-long-brcode");
        return body;
    }

    private static final IdempotencyRecord COMPLETED =
            new IdempotencyRecord(MERCHANT, KEY, ENDPOINT, FINGERPRINT, "COMPLETED", TXID.value(), 201, snapshotBody());

    private final FakeCache cache = new FakeCache();
    private final RecordingDelegate delegate = new RecordingDelegate();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final JsonMapper json = JsonMapper.builder().build();

    private CachedIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new CachedIdempotencyStore(delegate, cache, json, TTL, metrics);
    }

    private double counter(String name) {
        var found = metrics.find(name).counter();
        return found == null ? 0.0 : found.count();
    }

    // ------------------------------------------------------------- cache hit path

    @Test
    void cache_hit_returns_the_completed_record_without_touching_the_database() {
        cache.put(keyString(), json.writeValueAsString(COMPLETED), TTL);

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, FINGERPRINT);

        assertThat(existing).isPresent();
        assertThat(existing.get().state()).isEqualTo("COMPLETED");
        assertThat(existing.get().requestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(existing.get().responseBody()).isEqualTo(COMPLETED.responseBody());
        assertThat(delegate.insertIfAbsentCalls).isZero();
        assertThat(counter("dargent.cache.hits")).isEqualTo(1.0);
        assertThat(counter("dargent.cache.misses")).isZero();
    }

    @Test
    void cached_replay_preserves_the_fingerprint_so_a_conflicting_body_is_decidable() {
        // The use case compares the cached record's fingerprint with the incoming request's —
        // if the cache dropped it, every replay would degrade to 409 (null != fingerprint).
        cache.put(keyString(), json.writeValueAsString(COMPLETED), TTL);

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, "different-fingerprint");

        assertThat(existing).isPresent();
        assertThat(existing.get().requestFingerprint()).isEqualTo(FINGERPRINT);
    }

    // ------------------------------------------------------------- cache miss path

    @Test
    void cache_miss_reads_the_delegate_and_reads_through_completed_rows() {
        delegate.existing = COMPLETED;

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, FINGERPRINT);

        assertThat(existing).contains(COMPLETED);
        assertThat(delegate.insertIfAbsentCalls).isEqualTo(1);
        assertThat(counter("dargent.cache.misses")).isEqualTo(1.0);
        assertThat(cache.store).containsKey(keyString());
        IdempotencyRecord cached = json.readValue(cache.store.get(keyString()), IdempotencyRecord.class);
        assertThat(cached.state()).isEqualTo("COMPLETED");
        assertThat(cached.requestFingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void in_flight_rows_are_never_cached() {
        delegate.existing = new IdempotencyRecord(MERCHANT, KEY, ENDPOINT, FINGERPRINT, "IN_FLIGHT", null, null, null);

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, FINGERPRINT);

        assertThat(existing).isPresent();
        assertThat(existing.get().state()).isEqualTo("IN_FLIGHT");
        assertThat(cache.store).isEmpty();
    }

    @Test
    void inserted_keys_return_empty_and_write_nothing_to_the_cache() {
        delegate.existing = null; // INSERT succeeded → caller owns the row

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, FINGERPRINT);

        assertThat(existing).isEmpty();
        assertThat(cache.store).isEmpty();
    }

    // ------------------------------------------------------------- write-through / evict

    @Test
    void mark_completed_writes_through_the_completed_snapshot_with_fingerprint() {
        store.markCompleted(
                MERCHANT, KEY, ENDPOINT, new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C"), 201, snapshotBody(), FINGERPRINT);

        assertThat(delegate.markCompletedCalls).isEqualTo(1);
        assertThat(cache.store).containsKey(keyString());
        IdempotencyRecord cached = json.readValue(cache.store.get(keyString()), IdempotencyRecord.class);
        assertThat(cached.state()).isEqualTo("COMPLETED");
        assertThat(cached.requestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(cached.responseStatus()).isEqualTo(201);
    }

    @Test
    void delete_evicts_the_cached_entry() {
        cache.put(keyString(), json.writeValueAsString(COMPLETED), TTL);

        store.delete(MERCHANT, KEY, ENDPOINT);

        assertThat(delegate.deleteCalls).isEqualTo(1);
        assertThat(cache.store).doesNotContainKey(keyString());
    }

    // ------------------------------------------------------------- fail-open

    @Test
    void redis_read_failure_falls_back_to_the_database_and_counts_fail_open() {
        delegate.existing = COMPLETED;
        cache.failGet = true;

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, FINGERPRINT);

        assertThat(existing).contains(COMPLETED); // correctness unchanged — DB fallback
        assertThat(counter("dargent.cache.failopen")).isEqualTo(1.0);
        assertThat(counter("dargent.cache.hits")).isZero();
    }

    @Test
    void redis_write_failure_is_absorbed_and_never_breaks_the_money_path() {
        cache.failPut = true;

        assertThatCode(() -> store.markCompleted(
                        MERCHANT,
                        KEY,
                        ENDPOINT,
                        new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C"),
                        201,
                        snapshotBody(),
                        FINGERPRINT))
                .doesNotThrowAnyException();

        assertThat(delegate.markCompletedCalls).isEqualTo(1); // DB write happened; only the cache copy failed
        assertThat(counter("dargent.cache.failopen")).isEqualTo(1.0);
    }

    @Test
    void redis_evict_failure_is_absorbed_and_the_delete_still_happens() {
        cache.failDelete = true;

        assertThatCode(() -> store.delete(MERCHANT, KEY, ENDPOINT)).doesNotThrowAnyException();

        assertThat(delegate.deleteCalls).isEqualTo(1);
        assertThat(counter("dargent.cache.failopen")).isEqualTo(1.0);
    }

    @Test
    void corrupt_cached_value_fails_open_to_the_database() {
        delegate.existing = COMPLETED;
        cache.put(keyString(), "{not json", TTL);

        Optional<IdempotencyRecord> existing = store.insertIfAbsent(MERCHANT, KEY, ENDPOINT, FINGERPRINT);

        assertThat(existing).contains(COMPLETED);
        assertThat(counter("dargent.cache.failopen")).isEqualTo(1.0);
    }

    // ------------------------------------------------------------- fixtures

    private String keyString() {
        return "dargent:idempotency-replay:" + MERCHANT + ":" + ENDPOINT + ":" + KEY;
    }

    /** In-memory fake of the outside world (Redis) — no mocking framework, deterministic. */
    private static final class FakeCache implements ReplayCacheClient {
        final Map<String, String> store = new HashMap<>();
        boolean failGet;
        boolean failPut;
        boolean failDelete;

        @Override
        public Optional<String> get(String key) {
            if (failGet) {
                throw new IllegalStateException("redis down (simulated)");
            }
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(String key, String jsonValue, Duration ttl) {
            if (failPut) {
                throw new IllegalStateException("redis down (simulated)");
            }
            store.put(key, jsonValue);
        }

        @Override
        public void delete(String key) {
            if (failDelete) {
                throw new IllegalStateException("redis down (simulated)");
            }
            store.remove(key);
        }
    }

    /** Hand-rolled delegate recorder — the port implementation under decoration. */
    private static final class RecordingDelegate implements IdempotencyStore {
        IdempotencyRecord existing;
        int insertIfAbsentCalls;
        int markCompletedCalls;
        int deleteCalls;

        @Override
        public Optional<IdempotencyRecord> insertIfAbsent(
                UUID merchantId, String idempotencyKey, String endpoint, String requestFingerprint) {
            insertIfAbsentCalls++;
            return Optional.ofNullable(existing);
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
            markCompletedCalls++;
        }

        @Override
        public void delete(UUID merchantId, String idempotencyKey, String endpoint) {
            deleteCalls++;
        }
    }
}

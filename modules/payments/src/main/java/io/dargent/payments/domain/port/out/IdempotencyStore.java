package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency store port (E3 spec §5.6): insert-or-get on (merchant_id, key, endpoint) →
 * returns existing if duplicate (caller handles 425/409); on core success, transition to
 * COMPLETED with snapshot; on PSP exhaustion, delete row (no snapshot).
 */
public interface IdempotencyStore {

    /**
     * Tries to insert an IN_FLIGHT row. Returns empty if inserted; returns existing record
     * if duplicate key (caller must compare fingerprint and decide 425 vs 409).
     */
    Optional<IdempotencyRecord> insertIfAbsent(UUID merchantId, String idempotencyKey, String endpoint,
            String requestFingerprint);

    /** Marks the key as COMPLETED with response snapshot (called after core + PSP success). */
    void markCompleted(UUID merchantId, String idempotencyKey, String endpoint,
            Txid paymentTxid, int responseStatus, Map<String, Object> responseBody);

    /** Deletes the key row (called on PSP exhaustion — no snapshot, caller retries fresh). */
    void delete(UUID merchantId, String idempotencyKey, String endpoint);
}
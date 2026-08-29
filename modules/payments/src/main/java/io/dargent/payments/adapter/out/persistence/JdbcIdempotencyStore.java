package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the idempotency store. */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> insertIfAbsent(UUID merchantId, String idempotencyKey,
            String endpoint, String requestFingerprint) {
        return jdbc.sql("""
                insert into payments.idempotency_keys (merchant_id, idempotency_key, endpoint, request_fingerprint, state)
                values (:merchant, :key, :endpoint, :fingerprint, 'IN_FLIGHT')
                on conflict (merchant_id, idempotency_key, endpoint) do nothing
                returning merchant_id, idempotency_key, endpoint, request_fingerprint, state, payment_txid, response_status, response_body
                """)
                .param("merchant", merchantId)
                .param("key", idempotencyKey)
                .param("endpoint", endpoint)
                .param("fingerprint", requestFingerprint)
                .query(IdempotencyRecord.class)
                .optional();
    }

    @Override
    public void markCompleted(UUID merchantId, String idempotencyKey, String endpoint,
            Txid paymentTxid, int responseStatus, Map<String, Object> responseBody) {
        jdbc.sql("""
                update payments.idempotency_keys
                set state = 'COMPLETED', payment_txid = :txid, response_status = :status,
                    response_body = :body::jsonb, completed_at = now()
                where merchant_id = :merchant and idempotency_key = :key and endpoint = :endpoint
                """)
                .param("merchant", merchantId)
                .param("key", idempotencyKey)
                .param("endpoint", endpoint)
                .param("txid", paymentTxid.value())
                .param("status", responseStatus)
                .param("body", responseBody != null ? responseBody : Collections.emptyMap())
                .update();
    }

    @Override
    public void delete(UUID merchantId, String idempotencyKey, String endpoint) {
        jdbc.sql("""
                delete from payments.idempotency_keys
                where merchant_id = :merchant and idempotency_key = :key and endpoint = :endpoint
                """)
                .param("merchant", merchantId)
                .param("key", idempotencyKey)
                .param("endpoint", endpoint)
                .update();
    }
}
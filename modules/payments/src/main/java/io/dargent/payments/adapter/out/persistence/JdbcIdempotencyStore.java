package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JdbcClient implementation of the idempotency store. */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private IdempotencyRecord mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> body = null;
        Object rawBody = rs.getObject("response_body");
        if (rawBody != null) {
            try {
                body = jsonMapper.readValue(rawBody.toString(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse idempotency response_body", e);
            }
        }
        return new IdempotencyRecord(
                rs.getObject("merchant_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("endpoint"),
                rs.getString("request_fingerprint"),
                rs.getString("state"),
                rs.getString("payment_txid"),
                (Integer) rs.getObject("response_status"),
                body);
    }

    @Override
    public Optional<IdempotencyRecord> insertIfAbsent(UUID merchantId, String idempotencyKey,
            String endpoint, String requestFingerprint) {
        // On a fresh insert, RETURNING yields the new row; on the conflict path it yields NO rows.
        // Contract (CreatePaymentUseCase): empty => this caller inserted (won the race); present =>
        // a row already existed (replay or in-flight). So a row returned by the insert means "we
        // inserted" => return empty; a conflict means "already there" => re-read and return it.
        Optional<IdempotencyRecord> inserted = jdbc.sql("""
                insert into payments.idempotency_keys (merchant_id, idempotency_key, endpoint, request_fingerprint, state)
                values (:merchant, :key, :endpoint, :fingerprint, 'IN_FLIGHT')
                on conflict (merchant_id, idempotency_key, endpoint) do nothing
                returning merchant_id, idempotency_key, endpoint, request_fingerprint, state, payment_txid, response_status, response_body
                """)
                .param("merchant", merchantId)
                .param("key", idempotencyKey)
                .param("endpoint", endpoint)
                .param("fingerprint", requestFingerprint)
                .query(ROW_MAPPER)
                .optional();
        if (inserted.isPresent()) {
            return Optional.empty(); // we own the newly inserted row
        }
        return jdbc.sql("""
                select merchant_id, idempotency_key, endpoint, request_fingerprint, state,
                       payment_txid, response_status, response_body
                from payments.idempotency_keys
                where merchant_id = :merchant and idempotency_key = :key and endpoint = :endpoint
                """)
                .param("merchant", merchantId)
                .param("key", idempotencyKey)
                .param("endpoint", endpoint)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public void markCompleted(UUID merchantId, String idempotencyKey, String endpoint,
            Txid paymentTxid, int responseStatus, Map<String, Object> responseBody) {
        String bodyJson;
        try {
            bodyJson = responseBody != null
                    ? new tools.jackson.databind.json.JsonMapper().writeValueAsString(responseBody)
                    : "{}";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotency response body", e);
        }
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
                .param("body", bodyJson)
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
package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.port.out.OutboxWriter;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the outbox writer. */
@Repository
public class JdbcOutboxWriter implements OutboxWriter {

    private final JdbcClient jdbc;

    public JdbcOutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String aggregateId, String type, int version, String payloadJson, String requestId) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id)
                values (gen_random_uuid(), :aggregate, :type, :version, :payload::jsonb, :requestId)
                """)
                .param("aggregate", aggregateId)
                .param("type", type)
                .param("version", version)
                .param("payload", payloadJson)
                .param("requestId", requestId)
                .update();
    }
}
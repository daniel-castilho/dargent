package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.port.out.AuditWriter;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the audit writer. */
@Repository
public class JdbcAuditWriter implements AuditWriter {

    private final JdbcClient jdbc;

    public JdbcAuditWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String commandName, UUID actorKeyId, UUID merchantId, String aggregateId, String requestId) {
        jdbc.sql("""
                insert into payments.audit_log (id, command_name, actor_key_id, merchant_id, aggregate_id, request_id)
                values (gen_random_uuid(), :cmd, :actor, :merchant, :agg, :reqId)
                """)
                .param("cmd", commandName)
                .param("actor", actorKeyId)
                .param("merchant", merchantId)
                .param("agg", aggregateId)
                .param("reqId", requestId)
                .update();
    }
}
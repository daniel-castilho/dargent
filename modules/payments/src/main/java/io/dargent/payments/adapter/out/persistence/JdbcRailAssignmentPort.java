package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.RailAssignmentPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC rail assignment (M5 S0): reads/writes the {@code payments.payments.rail} column, the
 * infrastructure-routing seam the aggregate does not carry. This adapter owns the column — the JPA
 * entity deliberately does not map it, so Hibernate never touches it and {@code assign} rides the
 * create transaction via the caller's TransactionTemplate.
 */
@Repository
public class JdbcRailAssignmentPort implements RailAssignmentPort {

    private static final String DEFAULT_RAIL = "pix";

    private final JdbcClient jdbc;

    public JdbcRailAssignmentPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public String railOf(Txid txid) {
        return jdbc.sql("select rail from payments.payments where txid = :txid")
                .param("txid", txid.value())
                .query(String.class)
                .optional()
                .orElse(DEFAULT_RAIL);
    }

    @Override
    @Transactional
    public void assign(Txid txid, String rail) {
        jdbc.sql("update payments.payments set rail = :rail where txid = :txid")
                .param("rail", rail)
                .param("txid", txid.value())
                .update();
    }
}

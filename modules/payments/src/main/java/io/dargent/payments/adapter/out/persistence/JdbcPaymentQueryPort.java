package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PaymentQueryPort;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the payment query port (cursor pagination). */
@Repository
public class JdbcPaymentQueryPort implements PaymentQueryPort {

    private final JdbcClient jdbc;

    public JdbcPaymentQueryPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payment> findByTxid(UUID merchantId, Txid txid) {
        return jdbc.sql("""
                select * from payments.payments
                where txid = :txid and merchant_id = :merchant
                """)
                .param("txid", txid.value())
                .param("merchant", merchantId)
                .query(PaymentEntity.class)
                .optional()
                .map(PaymentMapper::toDomain);
    }

    @Override
    public List<Payment> findPage(UUID merchantId, String cursor, int limit) {
        String sql;
        Object[] params;
        if (cursor == null || cursor.isBlank()) {
            sql = """
                    select * from payments.payments
                    where merchant_id = :merchant
                    order by created_at desc, txid desc
                    limit :limit
                    """;
            params = new Object[]{"merchant", merchantId, "limit", limit};
        } else {
            // cursor = base64(txId|createdAtMicros)
            String[] parts = decodeCursor(cursor);
            String afterTxid = parts[0];
            long afterMicros = Long.parseLong(parts[1]);
            sql = """
                    select * from payments.payments
                    where merchant_id = :merchant
                      and (created_at < :afterMicros::timestamptz
                           or (created_at = :afterMicros::timestamptz and txid < :afterTxid))
                    order by created_at desc, txid desc
                    limit :limit
                    """;
            params = new Object[]{
                    "merchant", merchantId,
                    "afterMicros", afterMicros,
                    "afterTxid", afterTxid,
                    "limit", limit
            };
        }
        return jdbc.sql(sql).params(params).query(PaymentEntity.class).list().stream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    private String[] decodeCursor(String cursor) {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor));
        return decoded.split("\\|", 2);
    }

    static String encodeCursor(String txid, long createdAtMicros) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((txid + "|" + createdAtMicros).getBytes());
    }
}
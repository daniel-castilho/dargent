package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the payment repository. */
@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private final JdbcClient jdbc;
    private final PaymentMapper mapper;

    public JdbcPaymentRepository(JdbcClient jdbc, PaymentMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void save(Payment payment) {
        if (findByTxid(payment.txid()).isPresent()) {
            throw new DuplicatePaymentTxidException(payment.txid());
        }
        jdbc.sql("""
                insert into payments.payments (
                    id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation,
                    refunded_cents, created_at, confirmed_at
                ) values (
                    :id, :txid, :merchant, :description, :amount, :status, :version,
                    :expiresAt, :endToEndId, :fee, :net, :lateConfirmation,
                    :refunded, :createdAt, :confirmedAt
                )
                """)
                .param("id", payment.id())
                .param("txid", payment.txid().value())
                .param("merchant", payment.merchantId())
                .param("description", payment.description())
                .param("amount", payment.amount().cents())
                .param("status", payment.status().name())
                .param("version", payment.version())
                .param("expiresAt", payment.expiresAt())
                .param("endToEndId", payment.endToEndId() == null ? null : payment.endToEndId().value())
                .param("fee", payment.fee() == null ? null : payment.fee().cents())
                .param("net", payment.net() == null ? null : payment.net().cents())
                .param("lateConfirmation", payment.lateConfirmation())
                .param("refunded", payment.refunded().cents())
                .param("createdAt", payment.createdAt())
                .param("confirmedAt", payment.confirmedAt())
                .update();
    }

    @Override
    public Optional<Payment> findByTxid(Txid txid) {
        return jdbc.sql("""
                select * from payments.payments where txid = :txid
                """)
                .param("txid", txid.value())
                .query(PaymentEntity.class)
                .optional()
                .map(PaymentMapper::toDomain);
    }

    @Override
    public boolean updateIfVersionMatches(Payment payment, int expectedVersion) {
        int updated = jdbc.sql("""
                update payments.payments set
                    merchant_id = :merchant,
                    description = :description,
                    amount_cents = :amount,
                    status = :status,
                    version = :newVersion,
                    expires_at = :expiresAt,
                    end_to_end_id = :endToEndId,
                    fee_cents = :fee,
                    net_cents = :net,
                    late_confirmation = :lateConfirmation,
                    refunded_cents = :refunded,
                    created_at = :createdAt,
                    confirmed_at = :confirmedAt
                where txid = :txid and version = :expectedVersion
                """)
                .param("merchant", payment.merchantId())
                .param("description", payment.description())
                .param("amount", payment.amount().cents())
                .param("status", payment.status().name())
                .param("newVersion", expectedVersion + 1)
                .param("expiresAt", payment.expiresAt())
                .param("endToEndId", payment.endToEndId() == null ? null : payment.endToEndId().value())
                .param("fee", payment.fee() == null ? null : payment.fee().cents())
                .param("net", payment.net() == null ? null : payment.net().cents())
                .param("lateConfirmation", payment.lateConfirmation())
                .param("refunded", payment.refunded().cents())
                .param("createdAt", payment.createdAt())
                .param("confirmedAt", payment.confirmedAt())
                .param("txid", payment.txid().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 0) {
            return false;
        }
        // Update the payment's version to the new one
        // Note: in a real implementation we'd re-read to get the new version
        return true;
    }
}
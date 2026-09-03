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
    public java.util.List<Payment> findDueExpired(java.time.Instant now, int limit) {
        return jdbc.sql("""
                select * from payments.payments
                where status = 'PENDING' and expires_at < :now
                order by expires_at
                limit :limit
                """)
                .param("now", now)
                .param("limit", limit)
                .query(PaymentEntity.class)
                .list()
                .stream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    @Override
    public boolean expireIfDue(Payment payment, java.time.Instant now) {
        int updated = jdbc.sql("""
                update payments.payments set
                    status = 'EXPIRED',
                    version = version + 1
                where id = :id and status = 'PENDING' and expires_at < :now
                """)
                .param("id", payment.id())
                .param("now", now)
                .update();
        return updated != 0;
    }

    @Override
    public java.util.List<Payment> findDueReconciliation(java.time.Instant now, int limit) {
        return jdbc.sql("""
                select * from payments.payments
                where status in ('PENDING', 'EXPIRED')
                  and next_reconcile_at is not null
                  and next_reconcile_at <= :now
                order by next_reconcile_at
                limit :limit
                """)
                .param("now", java.sql.Timestamp.from(now))
                .param("limit", limit)
                .query(PaymentEntity.class)
                .list()
                .stream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    @Override
    public boolean updateReconciliationSchedule(Payment payment, java.time.Instant nextReconcileAt, int reconcileAttempts, int expectedVersion) {
        int updated = jdbc.sql("""
                update payments.payments set
                    next_reconcile_at = :nextReconcileAt,
                    reconcile_attempts = :reconcileAttempts,
                    version = :newVersion
                where id = :id and version = :expectedVersion
                """)
                .param("nextReconcileAt", java.sql.Timestamp.from(nextReconcileAt))
                .param("reconcileAttempts", reconcileAttempts)
                .param("newVersion", expectedVersion + 1)
                .param("id", payment.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated != 0;
    }

    @Override
    public boolean clearReconciliationScheduleIfPastWindow(Payment payment, java.time.Instant windowEnd, int expectedVersion) {
        int updated = jdbc.sql("""
                update payments.payments set
                    next_reconcile_at = NULL,
                    version = version + 1
                where id = :id and version = :expectedVersion
                  and status in ('PENDING', 'EXPIRED')
                  and next_reconcile_at is not null
                """)
                .param("id", payment.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated != 0;
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

    @Override
    public void insertRefund(UUID paymentId, String txid, long amountCents, long feeReversalCents, long netCents,
            String requestId) {
        jdbc.sql("""
                insert into payments.refunds (id, payment_id, txid, amount_cents, fee_reversal_cents,
                    net_cents, request_id, created_at)
                values (:id, :paymentId, :txid, :amount, :feeRev, :net, :requestId, now())
                """)
                .param("id", java.util.UUID.randomUUID())
                .param("paymentId", paymentId)
                .param("txid", txid)
                .param("amount", amountCents)
                .param("feeRev", feeReversalCents)
                .param("net", netCents)
                .param("requestId", requestId)
                .update();
    }

    @Override
    public Optional<Payment> findByTxidForUpdate(String txid) {
        return jdbc.sql("""
                select * from payments.payments where txid = :txid FOR UPDATE
                """)
                .param("txid", txid)
                .query(PaymentEntity.class)
                .optional()
                .map(PaymentMapper::toDomain);
    }
}
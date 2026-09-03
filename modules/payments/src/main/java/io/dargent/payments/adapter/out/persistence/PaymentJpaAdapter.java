package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA/Hibernate implementation of the lost-race contract (design.md §4.1, D6,
 * spec §6, AGENTS.md §3.2) at the adapter edge. A state transition is a
 * <em>conditional UPDATE</em> scoped by the {@code version} column: zero rows
 * affected means the race was lost and the method returns {@code false} — the
 * database arbitrates the race, and no exception ever escapes a lost race.
 * Note this is deliberately NOT a {@code flush}-then-catch-{@code ObjectOptimisticLockingFailureException}
 * approach: a failed flush marks the transaction rollback-only and the caller of
 * {@code updateIfVersionMatches} must still get {@code false} on a clean commit.
 * Domain stays framework-free (D14).
 */
@Repository
public class PaymentJpaAdapter implements PaymentRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void save(Payment payment) {
        if (findEntityByTxid(payment.txid().value()).isPresent()) {
            throw new DuplicatePaymentTxidException(payment.txid());
        }
        PaymentEntity entity = PaymentMapper.toEntity(payment);
        em.persist(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByTxid(Txid txid) {
        return findEntityByTxid(txid.value())
                .map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional
    public boolean updateIfVersionMatches(Payment payment, int expectedVersion) {
        Query update = em.createQuery(
                "update PaymentEntity p set "
                        + "p.merchantId = :merchantId, "
                        + "p.description = :description, "
                        + "p.amountCents = :amountCents, "
                        + "p.status = :status, "
                        + "p.expiresAt = :expiresAt, "
                        + "p.endToEndId = :endToEndId, "
                        + "p.feeCents = :feeCents, "
                        + "p.netCents = :netCents, "
                        + "p.lateConfirmation = :lateConfirmation, "
                        + "p.refundedCents = :refundedCents, "
                        + "p.createdAt = :createdAt, "
                        + "p.confirmedAt = :confirmedAt, "
                        + "p.version = :newVersion "
                        + "where p.txid = :txid and p.version = :expectedVersion");
        int updatedRows = update
                .setParameter("merchantId", payment.merchantId())
                .setParameter("description", payment.description())
                .setParameter("amountCents", payment.amount().cents())
                .setParameter("status", payment.status().name())
                .setParameter("expiresAt", payment.expiresAt())
                .setParameter("endToEndId", payment.endToEndId() == null ? null : payment.endToEndId().value())
                .setParameter("feeCents", payment.fee() == null ? null : payment.fee().cents())
                .setParameter("netCents", payment.net() == null ? null : payment.net().cents())
                .setParameter("lateConfirmation", payment.lateConfirmation())
                .setParameter("refundedCents", payment.refunded().cents())
                .setParameter("createdAt", payment.createdAt())
                .setParameter("confirmedAt", payment.confirmedAt())
                .setParameter("newVersion", expectedVersion + 1)
                .setParameter("txid", payment.txid().value())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        if (updatedRows == 0) {
            return false;
        }
        em.clear();
        PaymentEntity stored = findEntityByTxid(payment.txid().value()).orElseThrow();
        payment.markPersistedVersion(stored.getVersion());
        return true;
    }

    private Optional<PaymentEntity> findEntityByTxid(String txid) {
        return em.createQuery(
                        "select p from PaymentEntity p where p.txid = :txid", PaymentEntity.class)
                .setParameter("txid", txid)
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<Payment> findDueExpired(java.time.Instant now, int limit) {
        return em.createQuery(
                        "select p from PaymentEntity p "
                                + "where p.status = 'PENDING' and p.expiresAt < :now "
                                + "order by p.expiresAt", PaymentEntity.class)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultStream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean expireIfDue(Payment payment, java.time.Instant now) {
        Query update = em.createQuery(
                "update PaymentEntity p set p.status = 'EXPIRED', p.version = p.version + 1 "
                        + "where p.id = :id and p.status = 'PENDING' and p.expiresAt < :now");
        int updatedRows = update
                .setParameter("id", payment.id())
                .setParameter("now", now)
                .executeUpdate();
        if (updatedRows == 0) {
            return false;
        }
        em.clear();
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<Payment> findDueReconciliation(java.time.Instant now, int limit) {
        return em.createQuery(
                        "select p from PaymentEntity p "
                                + "where p.status in ('PENDING','EXPIRED') and p.nextReconcileAt is not null "
                                + "and p.nextReconcileAt <= :now "
                                + "order by p.nextReconcileAt", PaymentEntity.class)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultStream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean updateReconciliationSchedule(Payment payment, java.time.Instant nextReconcileAt, int reconcileAttempts, int expectedVersion) {
        Query update = em.createQuery(
                "update PaymentEntity p set "
                        + "p.nextReconcileAt = :nextReconcileAt, "
                        + "p.reconcileAttempts = :reconcileAttempts, "
                        + "p.version = :newVersion "
                        + "where p.id = :id and p.version = :expectedVersion");
        int updatedRows = update
                .setParameter("nextReconcileAt", nextReconcileAt)
                .setParameter("reconcileAttempts", reconcileAttempts)
                .setParameter("newVersion", expectedVersion + 1)
                .setParameter("id", payment.id())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        if (updatedRows == 0) {
            return false;
        }
        em.clear();
        PaymentEntity stored = findEntityByTxid(payment.txid().value()).orElseThrow();
        payment.markPersistedVersion(stored.getVersion());
        return true;
    }

    @Override
    @Transactional
    public boolean clearReconciliationScheduleIfPastWindow(Payment payment, java.time.Instant windowEnd, int expectedVersion) {
        Query update = em.createQuery(
                "update PaymentEntity p set "
                        + "p.nextReconcileAt = NULL, "
                        + "p.version = p.version + 1 "
                        + "where p.id = :id and p.version = :expectedVersion "
                        + "and p.status in ('PENDING','EXPIRED') "
                        + "and p.nextReconcileAt is not null");
        int updatedRows = update
                .setParameter("id", payment.id())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        if (updatedRows == 0) {
            return false;
        }
        em.clear();
        return true;
    }

    @Override
    @Transactional
    public Optional<Payment> findByTxidForUpdate(String txid) {
        var query = em.createQuery(
                "select p from PaymentEntity p where p.txid = :txid", PaymentEntity.class)
                .setParameter("txid", txid)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return query.getResultStream()
                .findFirst()
                .map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional
    public void insertRefund(UUID paymentId, String txid, long amountCents, long feeReversalCents,
            long netCents, String requestId) {
        em.createNativeQuery("""
                INSERT INTO payments.refunds (id, payment_id, txid, amount_cents,
                    fee_reversal_cents, net_cents, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """)
                .setParameter(1, java.util.UUID.randomUUID())
                .setParameter(2, paymentId)
                .setParameter(3, txid)
                .setParameter(4, amountCents)
                .setParameter(5, feeReversalCents)
                .setParameter(6, netCents)
                .setParameter(7, requestId)
                .executeUpdate();
    }
}
package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA/Hibernate implementation of the lost-race contract (design.md §4.1, D6,
 * spec §6) at the adapter edge. The {@code @Version} column keeps the optimistic
 * guard; a lost race MATERIALIZES as {@link ObjectOptimisticLockingFailureException}
 * and is translated to {@code false} here — never thrown through. Domain stays
 * framework-free (D14).
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
        var existingOpt = findEntityByTxid(payment.txid().value());
        if (existingOpt.isEmpty() || existingOpt.get().getVersion() != expectedVersion) {
            return false;
        }
        PaymentEntity existing = existingOpt.get();
        PaymentMapper.copyFieldsInto(payment, existing);
        try {
            em.flush();
        } catch (jakarta.persistence.OptimisticLockException e) {
            em.clear();
            return false;
        } catch (ObjectOptimisticLockingFailureException e) {
            em.clear();
            return false;
        }
        em.refresh(existing);
        payment.markPersistedVersion(existing.getVersion());
        return true;
    }

    private Optional<PaymentEntity> findEntityByTxid(String txid) {
        return em.createQuery(
                        "select p from PaymentEntity p where p.txid = :txid", PaymentEntity.class)
                .setParameter("txid", txid)
                .getResultStream()
                .findFirst();
    }
}
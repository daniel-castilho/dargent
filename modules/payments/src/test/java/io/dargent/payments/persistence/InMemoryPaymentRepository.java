package io.dargent.payments.persistence;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.PaymentRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link PaymentRepository} fake with faithful lost-race semantics
 * (spec §6): snapshot-on-write and snapshot-on-read (like a real reload — no
 * aliasing back onto the caller's aggregate), an expected-version guard, never
 * throws on a lost race. Test scope only — production uses the JPA adapter.
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<Txid, Payment> store = new LinkedHashMap<>();

    @Override
    public void save(Payment payment) {
        if (store.containsKey(payment.txid())) {
            throw new DuplicatePaymentTxidException(payment.txid());
        }
        store.put(payment.txid(), cloneViaRestore(payment));
    }

    @Override
    public Optional<Payment> findByTxid(Txid txid) {
        var stored = store.get(txid);
        return stored == null ? Optional.empty() : Optional.of(cloneViaRestore(stored));
    }

    @Override
    public boolean updateIfVersionMatches(Payment payment, int expectedVersion) {
        var current = store.get(payment.txid());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        store.put(payment.txid(), cloneViaRestore(payment));
        return true;
    }

    @Override
    public java.util.List<Payment> findDueExpired(java.time.Instant now, int limit) {
        return store.values().stream()
                .filter(p -> p.status() == io.dargent.payments.domain.model.PaymentStatus.PENDING)
                .filter(p -> p.expiresAt().isBefore(now))
                .sorted(java.util.Comparator.comparing(Payment::expiresAt))
                .limit(limit)
                .map(InMemoryPaymentRepository::cloneViaRestore)
                .toList();
    }

    @Override
    public boolean expireIfDue(Payment payment, java.time.Instant now) {
        var current = store.get(payment.txid());
        if (current == null || current.status() != io.dargent.payments.domain.model.PaymentStatus.PENDING
                || !current.expiresAt().isBefore(now)) {
            return false;
        }
        Payment expired = Payment.restore(
                current.id(), current.txid(), current.merchantId(), current.amount(), current.description(),
                current.expiresAt(), current.createdAt(),
                io.dargent.payments.domain.model.PaymentStatus.EXPIRED, current.version() + 1,
                current.endToEndId(), current.fee(), current.net(),
                current.lateConfirmation(), current.confirmedAt(), current.refunded().cents(), null, 0);
        store.put(payment.txid(), expired);
        return true;
    }

    /** Rebuilds a detached snapshot via the adapter-only {@code restore} factory. */
    private static Payment cloneViaRestore(Payment p) {
        return Payment.restore(
                p.id(), p.txid(), p.merchantId(), p.amount(), p.description(),
                p.expiresAt(), p.createdAt(),
                p.status(), p.version(),
                p.endToEndId(), p.fee(), p.net(),
                p.lateConfirmation(), p.confirmedAt(),
                p.refunded().cents(), p.nextReconcileAt(), p.reconcileAttempts());
    }

    @Override
    public java.util.List<Payment> findDueReconciliation(java.time.Instant now, int limit) {
        return store.values().stream()
                .filter(p -> p.status() == io.dargent.payments.domain.model.PaymentStatus.PENDING
                        || p.status() == io.dargent.payments.domain.model.PaymentStatus.EXPIRED)
                .filter(p -> p.nextReconcileAt() != null && p.nextReconcileAt().isBefore(now))
                .sorted(java.util.Comparator.comparing(Payment::nextReconcileAt))
                .limit(limit)
                .map(InMemoryPaymentRepository::cloneViaRestore)
                .toList();
    }

    @Override
    public boolean updateReconciliationSchedule(Payment payment, java.time.Instant nextReconcileAt, int reconcileAttempts, int expectedVersion) {
        var current = store.get(payment.txid());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        store.put(payment.txid(), Payment.restore(
                current.id(), current.txid(), current.merchantId(), current.amount(), current.description(),
                current.expiresAt(), current.createdAt(),
                current.status(), expectedVersion + 1,
                current.endToEndId(), current.fee(), current.net(),
                current.lateConfirmation(), current.confirmedAt(),
                current.refunded().cents(), nextReconcileAt, reconcileAttempts));
        return true;
    }

    @Override
    public boolean clearReconciliationScheduleIfPastWindow(Payment payment, java.time.Instant windowEnd, int expectedVersion) {
        var current = store.get(payment.txid());
        if (current == null || current.version() != expectedVersion
                || current.nextReconcileAt() == null) {
            return false;
        }
        store.put(payment.txid(), Payment.restore(
                current.id(), current.txid(), current.merchantId(), current.amount(), current.description(),
                current.expiresAt(), current.createdAt(),
                current.status(), expectedVersion + 1,
                current.endToEndId(), current.fee(), current.net(),
                current.lateConfirmation(), current.confirmedAt(),
                current.refunded().cents(), null, 0));
        return true;
    }
}
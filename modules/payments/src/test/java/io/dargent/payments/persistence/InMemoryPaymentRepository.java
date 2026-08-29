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

    /** Rebuilds a detached snapshot via the adapter-only {@code restore} factory. */
    private static Payment cloneViaRestore(Payment p) {
        return Payment.restore(
                p.id(), p.txid(), p.merchantId(), p.amount(), p.description(),
                p.expiresAt(), p.createdAt(),
                p.status(), p.version(),
                p.endToEndId(), p.fee(), p.net(),
                p.lateConfirmation(), p.confirmedAt(),
                p.refunded().cents());
    }
}
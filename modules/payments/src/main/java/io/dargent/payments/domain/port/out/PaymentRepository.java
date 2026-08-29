package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import java.util.Optional;

/**
 * Persistence seam for the payment aggregate. LOST-RACE CONTRACT (spec §6):
 * <ul>
 *   <li>{@code updateIfVersionMatches} returns {@code false} when the row changed
 *       underneath the caller (version consumed) — the aggregate passed in is then
 *       <em>stale</em> and the caller MUST re-read and re-decide. An adapter NEVER
 *       throws on a lost race.</li>
 *   <li>{@code save} is the idempotency/insert path: a duplicate {@code txid} is
 *       rejected with {@link DuplicatePaymentTxidException} (adapter-agnostic),
 *       letting callers regenerate the txid (E3).</li>
 * </ul>
 * The database arbitrates races (DRY: design.md §4.1, D6); the entity's domain
 * guard is the first line, the conditional update the last.
 */
public interface PaymentRepository {

    void save(Payment payment);

    Optional<Payment> findByTxid(Txid txid);

    /** @return true if the row updated (version consumed, aggregate bumped); false = lost race. */
    boolean updateIfVersionMatches(Payment payment, int expectedVersion);
}
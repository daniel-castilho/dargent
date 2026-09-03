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

    /**
     * Expiration scan (spec §3): PENDING payments whose deadline has passed, bounded by {@code limit}
     * and ordered to keep the tick stable. The V111 partial index serves this predicate.
     */
    java.util.List<Payment> findDueExpired(java.time.Instant now, int limit);

    /**
     * Conditional PENDING→EXPIRED transition (spec §3): true only if the row is still PENDING and past
     * its deadline — zero rows means the webhook/confirm won the race (or it already expired), so the
     * caller must no-op without outbox/audit writes. The DB, not the domain, arbitrates this race.
     */
    boolean expireIfDue(Payment payment, java.time.Instant now);
}
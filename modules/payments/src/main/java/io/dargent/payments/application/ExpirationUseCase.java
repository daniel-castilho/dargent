package io.dargent.payments.application;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ExpirationUseCase (spec §3): runs one tick of the expiration scheduler.
 * <p>
 * Spring-free use case owned by the payments module (scheduler mains Spring-free, §8). A tick is
 * <em>not</em> transactional as a whole: the scan runs on the V111 partial-index predicate, then
 * EACH due payment is transitioned in its own short {@link TransactionTemplate} tx — a tx spanning
 * the whole scan would be the defect (spec §3). Every transition is a conditional
 * {@code PENDING→EXPIRED WHERE expires_at < now()}; a zero-row update means the webhook/confirm won
 * the race or it already expired → the caller no-ops with zero writes. Verified idempotent: re-expire
 * of the same due list finds nothing left due until new deadlines pass.
 */
public final class ExpirationUseCase {

    private static final String BRL = "BRL";

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final EventEnvelopeFactory envelopeFactory;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final PaymentsMetrics metrics;

    public ExpirationUseCase(PaymentRepository paymentRepository, OutboxWriter outboxWriter,
            AuditWriter auditWriter, EventEnvelopeFactory envelopeFactory,
            TransactionTemplate txTemplate, Clock clock, PaymentsMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.envelopeFactory = envelopeFactory;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @param batch max due payments to expire in this tick (DARGENT_EXPIRATION_BATCH)
     * @return number of payments actually expired (nonzero only from won races)
     */
    public int runOnce(int batch) {
        Instant now = clock.instant();
        int expired = 0;
        for (Payment due : paymentRepository.findDueExpired(now, batch)) {
            if (expireOne(due, now)) {
                expired++;
            }
        }
        return expired;
    }

    /** Expire one payment in its own transaction; race loser → false, zero writes. */
    private boolean expireOne(Payment due, Instant now) {
        return txTemplate.execute(status -> {
            Payment current = paymentRepository.findByTxid(due.txid()).orElse(due);
            if (current.status() != io.dargent.payments.domain.model.PaymentStatus.PENDING
                    || !current.isDueForExpiration(now)) {
                return false;
            }
            current.expire(now);
            if (!paymentRepository.expireIfDue(current, now)) {
                // 0 rows: webhook/confirm won the race (or already expired) — no outbox, no audit
                return false;
            }
            metrics.transition("PENDING", "EXPIRED", "expiry");
            appendExpiredOutbox(current, now);
            auditWriter.record("expire_payment", null, current.merchantId(), current.txid().value(), null);
            return true;
        });
    }

    private void appendExpiredOutbox(Payment payment, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txid", payment.txid().value());
        payload.put("expiresAt", payment.expiresAt().toString());
        payload.put("amountCents", payment.amount().cents());
        String envelope = envelopeFactory.envelope("payment.expired", 1, payment.txid().value(),
                payment.merchantId(), null, payload, now);
        outboxWriter.append(payment.txid().value(), "payment.expired", 1, envelope, null);
    }
}
package io.dargent.payments.application;

import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.PspPort.CobState;
import io.dargent.payments.domain.port.out.PspPort.CobStatus;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ReconciliationUseCase (spec §4): polls the PSP truth endpoint and reconciles PENDING payments.
 * <p>
 * Spring-free use case owned by the payments module. A cycle is <em>not</em> transactional as a
 * whole: each payment is handled in its own short {@link TransactionTemplate} tx. Per PSP state:
 * <ul>
 *   <li><b>PAID</b> → the shared confirm/resurrect path (spec §5): PENDING→CONFIRMED {@code late=false}
 *       or EXPIRED→CONFIRMED {@code late=true}; fee computed at 100 bps exactly as E4; outbox
 *       {@code payment.confirmed}; audit {@code confirm_from_reconciliation} (NULL actor).</li>
 *   <li><b>EXPIRED</b> → local conditional expire as spec §3 (audit {@code expire_payment}).</li>
 *   <li><b>ACTIVE/OPEN</b> → advance the ladder: {@code next_reconcile_at = now + backoff[min(attempts, cap)]},
 *       {@code reconcile_attempts + 1}.</li>
 * </ul>
 * Give-up window: when {@code now() > expires_at + give-up}, clear the schedule and audit
 * {@code reconciliation_window_expired}; the PENDING row stays PENDING (no fake terminal state).
 * Amount mismatch on a PSP PAID → do NOT confirm, audit {@code reconciliation_amount_mismatch},
 * stay scheduled (incident territory). All transitions are conditional; a lost race no-ops with
 * zero writes (blue-green duplicate scheduler safety).
 */
public final class ReconciliationUseCase {

    private static final long FEE_BPS = 100L;
    private static final String BRL = "BRL";

    private final PaymentRepository paymentRepository;
    private final PspPort pspPort;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final EventEnvelopeFactory envelopeFactory;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final List<Duration> backoffLadder;
    private final Duration giveUpWindow;

    public ReconciliationUseCase(PaymentRepository paymentRepository, PspPort pspPort,
            OutboxWriter outboxWriter, AuditWriter auditWriter,
            EventEnvelopeFactory envelopeFactory, TransactionTemplate txTemplate,
            Clock clock, List<Duration> backoffLadder, Duration giveUpWindow) {
        this.paymentRepository = paymentRepository;
        this.pspPort = pspPort;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.envelopeFactory = envelopeFactory;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.backoffLadder = backoffLadder;
        this.giveUpWindow = giveUpWindow;
    }

    /**
     * Runs one reconciliation cycle.
     *
     * @param batch max due payments to reconcile in this cycle
     * @return number of payments whose attempt produced a state change (won race)
     */
    public int runOnce(int batch) {
        Instant now = clock.instant();
        int reconciled = 0;
        for (Payment due : paymentRepository.findDueReconciliation(now, batch)) {
            if (reconcileOne(due, now)) {
                reconciled++;
            }
        }
        return reconciled;
    }

    /** Reconcile one payment in its own transaction; race loser → false, zero writes. */
    private boolean reconcileOne(Payment due, Instant now) {
        return txTemplate.execute(status -> {
            Payment current = paymentRepository.findByTxid(due.txid()).orElse(due);
            // Scan returns PENDING and EXPIRED (TD-21): resurrection keeps EXPIRED rows in the
            // poll within the give-up window — the PSP may report PAID after reporting expired.
            if (current.status() != PaymentStatus.PENDING && current.status() != PaymentStatus.EXPIRED) {
                // Nothing to reconcile (already CONFIRMED/etc.) — no-op.
                return false;
            }
            if (pastGiveUpWindow(current, now)) {
                return clearScheduleAndAuditGiveUp(current, now);
            }
            CobStatus cob;
            try {
                cob = pspPort.getCob(current.txid());
            } catch (RuntimeException e) {
                // PSP unreachable → keep scheduled; next attempt advances the ladder.
                return advanceLadder(current, now);
            }
            switch (cob.state()) {
                case PAID -> reconcilePaid(current, cob, now);
                case EXPIRED -> reconcileExpired(current, now);
                case OPEN -> advanceLadder(current, now);
            }
            return true;
        });
    }

    private boolean pastGiveUpWindow(Payment payment, Instant now) {
        return payment.nextReconcileAt() != null
                && now.isAfter(payment.expiresAt().plus(giveUpWindow));
    }

    private boolean clearScheduleAndAuditGiveUp(Payment payment, Instant now) {
        Instant windowEnd = payment.expiresAt().plus(giveUpWindow);
        if (!paymentRepository.clearReconciliationScheduleIfPastWindow(payment, windowEnd, payment.version())) {
            return false; // lost race
        }
        auditWriter.record("reconciliation_window_expired", null, payment.merchantId(),
                payment.txid().value(), null);
        return true;
    }

    private boolean reconcilePaid(Payment payment, CobStatus cob, Instant now) {
        // Amount mismatch → do NOT confirm; audit and stay scheduled (incident territory).
        if (cob.amountCents() != payment.amount().cents()) {
            advanceLadder(payment, now);
            auditWriter.record("reconciliation_amount_mismatch", null, payment.merchantId(),
                    payment.txid().value(), null);
            return true;
        }
        int expectedVersion = payment.version();
        Payment working = payment;
        try {
            FeeBreakdown feeBreakdown = FeeBreakdown.of(payment.amount().cents(),
                    new io.dargent.payments.domain.model.BpsRate((int) FEE_BPS));
            working.confirm(new io.dargent.payments.domain.model.EndToEndId(cob.endToEndId()),
                    feeBreakdown, cob.paidAt() != null ? cob.paidAt() : now);
        } catch (IllegalArgumentException | io.dargent.payments.domain.exception.InvalidTransitionException e) {
            // Already terminal or illegal — treat as no-op (idempotent rerun path).
            return false;
        }
        if (!paymentRepository.updateIfVersionMatches(working, expectedVersion)) {
            return false; // lost race (already confirmed) → no-op, zero writes
        }
        appendConfirmedOutbox(working, now);
        auditWriter.record("confirm_from_reconciliation", null, payment.merchantId(),
                payment.txid().value(), null);
        return true;
    }

    private boolean reconcileExpired(Payment payment, Instant now) {
        // Already EXPIRED (PSP re-reports expired): nothing to transition — stay scheduled so a
        // late PAID can still resurrect. No outbox/audit write.
        if (payment.status() == PaymentStatus.EXPIRED) {
            return true;
        }
        int expectedVersion = payment.version();
        Payment working = payment;
        if (working.status() == PaymentStatus.PENDING && working.isDueForExpiration(now)) {
            try {
                working.expire(now);
            } catch (RuntimeException e) {
                return false;
            }
        } else {
            return false;
        }
        if (!paymentRepository.expireIfDue(working, now)) {
            return false; // 0 rows: confirm won, or already expired
        }
        appendExpiredOutbox(working, now);
        auditWriter.record("expire_payment", null, payment.merchantId(), payment.txid().value(), null);
        return true;
    }

    private boolean advanceLadder(Payment payment, Instant now) {
        int attempts = payment.reconcileAttempts();
        Duration backoff = backoffLadder.get(Math.min(attempts, backoffLadder.size() - 1));
        Instant next = now.plus(backoff);
        int nextAttempts = attempts + 1;
        int expectedVersion = payment.version();
        Payment fresh = paymentRepository.findByTxid(payment.txid()).orElse(payment);
        if (!paymentRepository.updateReconciliationSchedule(fresh, next, nextAttempts, expectedVersion)) {
            Payment current = paymentRepository.findByTxid(payment.txid()).orElse(payment);
            return false;
        }
        return true;
    }

    private void appendConfirmedOutbox(Payment payment, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", payment.amount().cents());
        payload.put("fee", payment.fee().cents());
        payload.put("net", payment.net().cents());
        payload.put("late", payment.lateConfirmation());
        String envelope = envelopeFactory.envelope("payment.confirmed", 1, payment.txid().value(),
                payment.merchantId(), null, payload, now);
        outboxWriter.append(payment.txid().value(), "payment.confirmed", 1, envelope, null);
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
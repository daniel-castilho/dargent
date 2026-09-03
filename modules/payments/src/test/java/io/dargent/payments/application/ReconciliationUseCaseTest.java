package io.dargent.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.PspPort.CobState;
import io.dargent.payments.domain.port.out.PspPort.CobStatus;
import io.dargent.payments.persistence.InMemoryPaymentRepository;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ReconciliationUseCase unit suite (spec §4): PSP PAID → confirm {@code late=false} with outbox +
 * audit {@code confirm_from_reconciliation} (NULL actor); PSP EXPIRED → local expire; PSP OPEN →
 * advance the ladder; give-up window → clear schedule + {@code reconciliation_window_expired};
 * amount mismatch → no confirm, {@code reconciliation_amount_mismatch}, stays scheduled;
 * resurrection (EXPIRED + PSP PAID) → confirm {@code late=true}. Uses the in-memory fake
 * (faithful conditional semantics) — never mocks the DB (AGENTS §3.9).
 */
class ReconciliationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final ZoneOffset OFFSET = ZoneOffset.UTC;
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Money AMOUNT = Money.of(10_000, "BRL");
    private static final List<Duration> LADDER = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1));

    private final FakeOutboxWriter outbox = new FakeOutboxWriter();
    private final FakeAuditWriter audit = new FakeAuditWriter();
    private final FakePspPort psp = new FakePspPort();
    private final InMemoryPaymentRepository repo = new InMemoryPaymentRepository();

    private ReconciliationUseCase useCase() {
        return new ReconciliationUseCase(repo, psp, outbox, audit,
                new EventEnvelopeFactory(new EventSerializer()),
                new DirectTransactionTemplate(), Clock.fixed(NOW, OFFSET),
                LADDER, Duration.ofHours(72));
    }

    @Test
    void psp_paid_confirm_with_late_false_outbox_and_null_actor_audit() {
        Payment payment = seedScheduled();

        int reconciled = useCase().runOnce(100);

        assertThat(reconciled).isEqualTo(1);
        Payment reloaded = repo.findByTxid(payment.txid()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(reloaded.lateConfirmation()).isFalse();

        assertThat(outbox.entries).hasSize(1);
        assertThat(outbox.entries.get(0).type()).isEqualTo("payment.confirmed");
        assertThat(outbox.entries.get(0).payload()).contains("\"late\":false")
                .contains("\"amount\":10000");

        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).commandName()).isEqualTo("confirm_from_reconciliation");
        assertThat(audit.entries.get(0).actorKeyId()).isNull();
    }

    @Test
    void psp_expired_expires_locally_with_outbox_and_expire_audit() {
        Payment payment = seedPending(Instant.parse("2026-09-01T10:00:00Z")); // past deadline → expire-able
        psp.state = CobState.EXPIRED;

        int reconciled = useCase().runOnce(100);

        assertThat(reconciled).isEqualTo(1);
        Payment reloaded = repo.findByTxid(payment.txid()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(outbox.entries).hasSize(1);
        assertThat(outbox.entries.get(0).type()).isEqualTo("payment.expired");
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).commandName()).isEqualTo("expire_payment");
    }

    @Test
    void psp_open_advances_the_backoff_ladder() {
        Payment payment = seedScheduled();
        psp.state = CobState.OPEN;

        int reconciled = useCase().runOnce(100);

        Payment reloaded = repo.findByTxid(payment.txid()).orElseThrow();
        // reconcileAttempts was 0 → ladder[0] = 1m; next poll lands 1m from now, attempts becomes 1.
        assertThat(reloaded.reconcileAttempts()).isEqualTo(1);
        assertThat(reloaded.nextReconcileAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(outbox.entries).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    void given_paid_cob_with_amount_mismatch_is_not_confirmed_audits_mismatch_and_stays_scheduled() {
        Payment payment = seedScheduled();
        psp.state = CobState.PAID;
        psp.amountCents = 9_999; // differs from payment.amount().cents() = 10000

        int reconciled = useCase().runOnce(100);

        Payment reloaded = repo.findByTxid(payment.txid()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.entries).isEmpty();
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).commandName()).isEqualTo("reconciliation_amount_mismatch");
        // stays scheduled (ladder advanced)
        assertThat(reloaded.nextReconcileAt()).isNotNull();
    }

    @Test
    void past_give_up_window_clears_schedule_and_audits_window_expired_without_confirm() {
        Payment payment = seedPending(
                Instant.parse("2026-08-30T09:00:00Z")); // expires long before now (give-up window 72h)
        psp.state = CobState.PAID;

        int reconciled = useCase().runOnce(100);

        Payment reloaded = repo.findByTxid(payment.txid()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.PENDING); // no fake terminal state
        assertThat(reloaded.nextReconcileAt()).isNull(); // unscheduled
        assertThat(reloaded.reconcileAttempts()).isZero();
        assertThat(outbox.entries).isEmpty();
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).commandName()).isEqualTo("reconciliation_window_expired");
    }

    /** EXPIRED locally + PSP PAID → resurrection {@code late=true}, exactly once. */
    @Test
    void expired_payment_resurrected_with_late_true_exactly_once() {
        Payment payment = seedExpiredLocally();
        psp.state = CobState.PAID;
        psp.paidAt = NOW.minusSeconds(30);

        int reconciled = useCase().runOnce(100);

        Payment reloaded = repo.findByTxid(payment.txid()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(reloaded.lateConfirmation()).isTrue();
        assertThat(outbox.entries).hasSize(1);
        assertThat(outbox.entries.get(0).type()).isEqualTo("payment.confirmed");
        assertThat(outbox.entries.get(0).payload()).contains("\"late\":true");

        // second run: already CONFIRMED → no-op, zero writes (idempotent)
        int second = useCase().runOnce(100);
        assertThat(second).isZero();
        assertThat(outbox.entries).hasSize(1);
        assertThat(audit.entries.stream().filter(e -> e.commandName().equals("confirm_from_reconciliation"))
                .count()).isEqualTo(1);
    }

    private Payment seedScheduled() {
        return seedPending(Instant.parse("2026-09-03T10:00:00Z")); // future expiry, within window
    }

    private Payment seedPending(Instant expiresAt) {
        Txid txid = new Txid(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25));
        Payment payment = Payment.create(txid, MERCHANT, AMOUNT, "order", expiresAt,
                expiresAt.minusSeconds(3600));
        // owner decision: initial schedule set at create — present here so findDueReconciliation finds it now
        payment.scheduleInitialReconciliation(Duration.ofMinutes(1),
                NOW.minusSeconds(61)); // first rung already elapsed → due now
        repo.save(payment);
        return payment;
    }

    private Payment seedExpiredLocally() {
        Txid txid = new Txid(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25));
        Instant expiresAt = Instant.parse("2026-09-01T10:00:00Z"); // past deadline → expire-able
        Payment base = Payment.create(txid, MERCHANT, AMOUNT, "order", expiresAt,
                expiresAt.minusSeconds(3600));
        base.expire(Instant.parse("2026-09-02T09:00:00Z")); // after deadline
        Payment expired = Payment.restore(
                base.id(), base.txid(), base.merchantId(), base.amount(), base.description(),
                base.expiresAt(), base.createdAt(), PaymentStatus.EXPIRED, base.version() + 1,
                null, null, null, false, null, 0,
                NOW.minusSeconds(61), 0);
        repo.save(expired);
        return expired;
    }

    // ------------------------------------------------------------------ fakes

    static class FakePspPort implements PspPort {
        CobState state = CobState.PAID;
        long amountCents = 10_000;
        String endToEndId = "E00416968202009221504E2345678910";
        Instant paidAt = NOW.minusSeconds(30);

        @Override
        public ChargeResult createCharge(CreateChargeInput input) {
            throw new UnsupportedOperationException("not used in reconciler tests");
        }

        @Override
        public CobStatus getCob(Txid txid) {
            return new CobStatus(txid, state, amountCents,
                    Instant.parse("2026-09-03T10:00:00Z"), endToEndId, paidAt);
        }
    }

    static class FakeOutboxWriter implements OutboxWriter {
        final List<OutboxEntry> entries = new java.util.ArrayList<>();

        record OutboxEntry(String aggregateId, String type, int version, String payload, String requestId) {}

        @Override
        public void append(String aggregateId, String type, int version, String payloadJson, String requestId) {
            entries.add(new OutboxEntry(aggregateId, type, version, payloadJson, requestId));
        }
    }

    static class FakeAuditWriter implements AuditWriter {
        final List<AuditEntry> entries = new java.util.ArrayList<>();

        record AuditEntry(String commandName, UUID actorKeyId, UUID merchantId, String aggregateId, String requestId) {}

        @Override
        public void record(String commandName, UUID actorKeyId, UUID merchantId, String aggregateId, String requestId) {
            entries.add(new AuditEntry(commandName, actorKeyId, merchantId, aggregateId, requestId));
        }
    }

    static class DirectTransactionTemplate extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}
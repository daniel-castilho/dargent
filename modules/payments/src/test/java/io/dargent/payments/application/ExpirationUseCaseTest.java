package io.dargent.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.persistence.InMemoryPaymentRepository;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ExpirationUseCase unit suite (spec §3): due → EXPIRED + outbox payment.expired + audit NULL
 * actor; not-due untouched; confirm-won race is a no-op with zero outbox/audit writes; shorter
 * deadline expires first; batch bounds the tick. Uses the in-memory repository fake (faithful
 * conditional semantics) — never mocks the DB (AGENTS §3.9).
 */
class ExpirationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final ZoneOffset OFFSET = ZoneOffset.UTC;
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Money AMOUNT = Money.of(10_000, "BRL");

    private final FakeOutboxWriter outbox = new FakeOutboxWriter();
    private final FakeAuditWriter audit = new FakeAuditWriter();
    private final InMemoryPaymentRepository repo = new InMemoryPaymentRepository();

    private ExpirationUseCase useCase() {
        return new ExpirationUseCase(repo, outbox, audit, new EventEnvelopeFactory(new EventSerializer()),
                new DirectTransactionTemplate(), Clock.fixed(NOW, OFFSET));
    }

    @Test
    void due_payment_is_expired_with_outbox_row_and_null_actor_audit() {
        Payment due = seedPending(Instant.parse("2026-09-02T09:00:00Z"));

        int expired = useCase().runOnce(100);

        assertThat(expired).isEqualTo(1);
        Payment reloaded = repo.findByTxid(due.txid()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(reloaded.version()).isEqualTo(1);

        assertThat(outbox.entries).hasSize(1);
        assertThat(outbox.entries.get(0).type()).isEqualTo("payment.expired");
        assertThat(outbox.entries.get(0).version()).isEqualTo(1);
        assertThat(outbox.entries.get(0).aggregateId()).isEqualTo(due.txid().value());
        assertThat(outbox.entries.get(0).payload()).contains("\"txid\":\"" + due.txid().value() + "\"")
                .contains("\"amountCents\":10000").contains("\"expiresAt\":\"");

        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).commandName()).isEqualTo("expire_payment");
        assertThat(audit.entries.get(0).actorKeyId()).isNull();
        assertThat(audit.entries.get(0).merchantId()).isEqualTo(MERCHANT);
        assertThat(audit.entries.get(0).aggregateId()).isEqualTo(due.txid().value());
    }

    @Test
    void not_due_or_already_expired_payments_are_left_untouched() {
        Payment pending = seedPending(Instant.parse("2026-09-02T10:01:00Z")); // future → not due
        Payment expired = seedPending(Instant.parse("2026-09-02T09:00:00Z"));
        // manually expire it via the repo (simulates a prior expiration tick)
        repo.expireIfDue(expired, Instant.parse("2026-09-02T09:00:01Z"));

        int expiredCount = useCase().runOnce(100);

        assertThat(expiredCount).isZero();
        assertThat(repo.findByTxid(pending.txid()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.entries).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    void batched_tick_only_expires_up_to_limit() {
        Payment first = seedPending(Instant.parse("2026-09-02T09:57:00Z"));
        Payment second = seedPending(Instant.parse("2026-09-02T09:58:00Z"));
        Payment third = seedPending(Instant.parse("2026-09-02T09:59:00Z"));

        int expired = useCase().runOnce(2);

        assertThat(expired).isEqualTo(2);
        // earliest two deadlines won the conditional update (ordered by expires_at)
        assertThat(repo.findByTxid(first.txid()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(repo.findByTxid(second.txid()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(repo.findByTxid(third.txid()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.entries).hasSize(2);
    }

    /** RSA-race no-op: a payment the webhook has already confirmed is never expired nor audited. */
    @Test
    void confirm_won_race_is_a_no_op_with_zero_outbox_and_audit_writes() {
        Payment due = seedPending(Instant.parse("2026-09-02T09:00:00Z"));
        // pretend the webhook confirmed it after the scan returned it
        Payment confirmed = repo.findByTxid(due.txid()).orElseThrow()
                .confirm(new io.dargent.payments.domain.model.EndToEndId("E00416968202009221504E2345678910"),
                        io.dargent.payments.domain.model.FeeBreakdown.of(10_000, new io.dargent.payments.domain.model.BpsRate(100)),
                        Instant.parse("2026-09-02T09:30:00Z"));
        repo.updateIfVersionMatches(confirmed, confirmed.version() - 1);

        int expired = useCase().runOnce(100);

        assertThat(expired).isZero();
        assertThat(repo.findByTxid(due.txid()).orElseThrow().status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(outbox.entries).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    private Instant now(Instant at) {
        return at;
    }

    private Payment seedPending(Instant expiresAt) {
        Txid txid = new Txid(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25));
        Payment payment = Payment.create(txid, MERCHANT, AMOUNT, "order", expiresAt,
                expiresAt.minusSeconds(3_600));
        repo.save(payment);
        return payment;
    }

    // ------------------------------------------------------------------ fakes

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
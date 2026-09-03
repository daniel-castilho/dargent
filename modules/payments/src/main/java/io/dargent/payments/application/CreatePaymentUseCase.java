package io.dargent.payments.application;

import io.dargent.payments.domain.br.BrCode;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.DuplicatePaymentTxidException;
import io.dargent.payments.domain.port.out.IdempotencyRecord;
import io.dargent.payments.domain.port.out.IdempotencyStore;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.PspPort;
import io.dargent.payments.domain.port.out.PspPort.ChargeResult;
import io.dargent.payments.domain.port.out.PspPort.CreateChargeInput;
import io.dargent.payments.domain.port.out.TxidGenerator;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CreatePaymentUseCase (E3 spec §5.8): an atomic core transaction, then a PSP phase strictly AFTER
 * commit, then a short success/exhaustion transaction. Fixes the E3R register:
 * <ul>
 *   <li>BD-1 — the core (idempotency row, payment, outbox, audit) runs in one {@link TransactionTemplate}.</li>
 *   <li>BD-2 — the aggregate lands canonically {@code PENDING}; {@code CONFIRMED} is reserved to the webhook.</li>
 *   <li>BD-3 — post-PSP writes re-read the aggregate and pass the row's current version to
 *       {@code updateIfVersionMatches}; the stale pre-PSP instance is never written.</li>
 *   <li>BD-4 — the PSP phase is a call to the port (the adapter owns the D19 retry/backoff/409 read-back).</li>
 *   <li>BD-5 — the outbox envelope carries the validated {@code requestId}.</li>
 *   <li>BD-6 — idempotency {@code COMPLETED} carries the exact 201 snapshot body; replay is zero-side-effect.</li>
 *   <li>BD-7 — the audit {@code actor_key_id} is the authenticated key's id, never generated.</li>
 *   <li>BD-8 — outbox payloads are serialized once through {@link EventSerializer} inside the envelope
 *   factory (no {@code String.format}); the column carries the full E3 §5.6 envelope (E6 owner decision).</li>
 *   <li>BD-9 — the PSP callback URL comes from the injected config value, never a literal.</li>
 * </ul>
 */
public final class CreatePaymentUseCase {

    private static final int TXID_REGENERATION_ATTEMPTS = 3;
    private static final String BRL = "BRL";

    private final PaymentRepository paymentRepo;
    private final IdempotencyStore idempotencyStore;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final PspPort pspPort;
    private final TxidGenerator txidGenerator;
    private final TransactionTemplate txTemplate;
    private final EventEnvelopeFactory envelopeFactory;
    private final String pixKey;
    private final String receiverName;
    private final String receiverCity;
    private final String pspCallbackUrl;
    private final Clock clock;
    private final Duration firstReconcileBackoff;

    public CreatePaymentUseCase(PaymentRepository paymentRepo, IdempotencyStore idempotencyStore,
            OutboxWriter outboxWriter, AuditWriter auditWriter, PspPort pspPort,
            TxidGenerator txidGenerator, TransactionTemplate txTemplate, EventEnvelopeFactory envelopeFactory,
            String pixKey, String receiverName, String receiverCity, String pspCallbackUrl, Clock clock,
            Duration firstReconcileBackoff) {
        this.paymentRepo = paymentRepo;
        this.idempotencyStore = idempotencyStore;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.pspPort = pspPort;
        this.txidGenerator = txidGenerator;
        this.txTemplate = txTemplate;
        this.envelopeFactory = envelopeFactory;
        this.pixKey = pixKey;
        this.receiverName = receiverName;
        this.receiverCity = receiverCity;
        this.pspCallbackUrl = pspCallbackUrl;
        this.clock = clock;
        this.firstReconcileBackoff = firstReconcileBackoff;
    }

    public Output execute(Input input) {
        Instant now = clock.instant();
        Instant expiresAtRequested = now.plus(input.expiresIn());

        // 1. Core transaction (atomic: idempotency IN_FLIGHT -> payment -> outbox -> audit)
        CoreOutcome core = txTemplate.execute(status -> runCore(input, now, expiresAtRequested));
        if (core.existing() != null) {
            return handleExisting(core.existing(), input);
        }
        Payment payment = core.payment();

        // 2. PSP phase, strictly after commit (BD-4). "First call" - no replay.
        ChargeResult psp;
        try {
            psp = pspPort.createCharge(new CreateChargeInput(payment.txid(), input.amount().cents(),
                    expiresAtRequested, pspCallbackUrl, input.description()));
        } catch (RuntimeException e) {
            runExhaustion(payment, input, now);
            throw new PspUnavailableException("psp_create_exhausted", e);
        }

        // 3. Success tx — PSP truth + COMPLETED + exact 201 snapshot (BD-3, BD-6)
        runSuccess(payment, psp, input, now);

        return new Output(psp.txid(), PaymentStatus.PENDING, psp.expiresAt(),
                composeBrCode(psp.txid(), input.amount()), false);
    }

    // ------------------------------------------------------------------ core

    private CoreOutcome runCore(Input input, Instant now, Instant expiresAtRequested) {
        var existing = idempotencyStore.insertIfAbsent(input.merchantId(), input.idempotencyKey(),
                input.endpoint(), input.requestFingerprint());
        if (existing.isPresent()) {
            return CoreOutcome.existing(existing.get()); // PK race loser: never creates a payment
        }
        Payment payment = createAndPersistPayment(input, now, expiresAtRequested);
        appendCreatedOutbox(payment, input, now);
        auditWriter.record("create_payment", input.apiKeyId(), input.merchantId(),
                payment.txid().value(), input.requestId());
        return CoreOutcome.created(payment);
    }

    /** Bounded txid regeneration on duplicate (E3 spec §5.8; ≤3 attempts). */
    private Payment createAndPersistPayment(Input input, Instant now, Instant expiresAtRequested) {
        for (int attempt = 1; ; attempt++) {
            Txid txid = txidGenerator.generate();
            Payment candidate = Payment.create(txid, input.merchantId(), input.amount(),
                    input.description(), expiresAtRequested, now);
            candidate.scheduleInitialReconciliation(firstReconcileBackoff, now);
            try {
                paymentRepo.save(candidate);
                return candidate;
            } catch (DuplicatePaymentTxidException e) {
                if (attempt >= TXID_REGENERATION_ATTEMPTS) {
                    throw new IllegalStateException("txid collision exhausted after " + attempt + " attempts", e);
                }
            }
        }
    }

    private void appendCreatedOutbox(Payment payment, Input input, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txid", payment.txid().value());
        payload.put("merchantId", payment.merchantId().toString());
        payload.put("amount", payment.amount().cents());
        payload.put("description", payment.description());
        payload.put("expiresAt", payment.expiresAt().toString());
        String envelope = envelopeFactory.envelope("payment.created", 1, payment.txid().value(),
                payment.merchantId(), input.requestId(), payload, now);
        outboxWriter.append(payment.txid().value(), "payment.created", 1, envelope, input.requestId());
    }

    // ------------------------------------------------------------- idempotency

    private Output handleExisting(IdempotencyRecord rec, Input input) {
        boolean sameFingerprint = rec.requestFingerprint().equals(input.requestFingerprint());
        if (!sameFingerprint) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency key conflict for key " + input.idempotencyKey());
        }
        if ("COMPLETED".equals(rec.state())) {
            return replay(rec); // zero side effects, byte-equal snapshot (BD-6)
        }
        throw new IdempotencyKeyInFlightException(
                "Idempotency key in flight for key " + input.idempotencyKey());
    }

    private Output replay(IdempotencyRecord rec) {
        Map<String, Object> body = rec.responseBody();
        Txid txid = new Txid(String.valueOf(body.get("txid")));
        PaymentStatus status = PaymentStatus.valueOf(String.valueOf(body.get("status")));
        Instant expiresAt = Instant.parse(String.valueOf(body.get("expiresAt")));
        String brcode = String.valueOf(body.get("brcode"));
        return new Output(txid, status, expiresAt, brcode, true);
    }

    // -------------------------------------------------------------- PSP phase

    private void runSuccess(Payment payment, ChargeResult psp, Input input, Instant now) {
        txTemplate.executeWithoutResult(t -> {
            Payment reRead = requireReRead(payment.txid());
            Payment updated = reRead.withExpiresAt(psp.expiresAt());
            int version = reRead.version();
            if (!paymentRepo.updateIfVersionMatches(updated, version)) {
                Payment latest = requireReRead(payment.txid());
                Payment latestUpdated = latest.withExpiresAt(psp.expiresAt());
                paymentRepo.updateIfVersionMatches(latestUpdated, latest.version());
            }
            String expiresIn = Duration.between(now, psp.expiresAt()).toString();
            Map<String, Object> snapshot = snapshotBody(psp.txid(), input.amount(), psp.expiresAt(),
                    expiresIn, composeBrCode(psp.txid(), input.amount()));
            idempotencyStore.markCompleted(input.merchantId(), input.idempotencyKey(), input.endpoint(),
                    psp.txid(), 201, snapshot);
        });
    }

    private void runExhaustion(Payment payment, Input input, Instant now) {
        txTemplate.executeWithoutResult(t -> {
            Payment reRead = requireReRead(payment.txid());
            int expectedVersion = reRead.version(); // DB version BEFORE the transition (BD-3)
            Payment failed = reRead.markFailed("psp_create_exhausted", clock.instant());
            if (!paymentRepo.updateIfVersionMatches(failed, expectedVersion)) {
                Payment latest = requireReRead(payment.txid());
                int latestVersion = latest.version();
                Payment latestFailed = latest.markFailed("psp_create_exhausted", clock.instant());
                paymentRepo.updateIfVersionMatches(latestFailed, latestVersion);
            }
            appendFailedOutbox(failed, input, now);
            idempotencyStore.delete(input.merchantId(), input.idempotencyKey(), input.endpoint());
        });
    }

    private Payment requireReRead(Txid txid) {
        return paymentRepo.findByTxid(txid)
                .orElseThrow(() -> new IllegalStateException("payment vanished during create: " + txid.value()));
    }

    private void appendFailedOutbox(Payment payment, Input input, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txid", payment.txid().value());
        payload.put("merchantId", payment.merchantId().toString());
        payload.put("amount", payment.amount().cents());
        payload.put("reason", "psp_create_exhausted");
        payload.put("failedAt", now.toString());
        String envelope = envelopeFactory.envelope("payment.failed", 1, payment.txid().value(),
                payment.merchantId(), input.requestId(), payload, now);
        outboxWriter.append(payment.txid().value(), "payment.failed", 1, envelope, input.requestId());
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> snapshotBody(Txid txid, Money amount, Instant expiresAt,
            String expiresIn, String brcode) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("txid", txid.value());
        snapshot.put("status", "PENDING");
        snapshot.put("amount", amount.cents());
        snapshot.put("currency", BRL);
        snapshot.put("expiresAt", expiresAt.toString());
        snapshot.put("brcode", brcode);
        snapshot.put("expiresIn", expiresIn);
        return snapshot;
    }

    private String composeBrCode(Txid txid, Money amount) {
        return BrCode.of(pixKey, receiverName, receiverCity, amount.cents(), txid);
    }

    // ----------------------------------------------------------------- models

    public record Input(
            UUID merchantId,
            UUID apiKeyId,
            String idempotencyKey,
            String endpoint,
            String requestFingerprint,
            String requestId,
            Money amount,
            String description,
            Duration expiresIn
    ) {}

    public record Output(
            Txid txid,
            PaymentStatus status,
            Instant expiresAt,
            String brcode,
            boolean replay
    ) {}

    private record CoreOutcome(Payment payment, IdempotencyRecord existing) {
        static CoreOutcome created(Payment payment) {
            return new CoreOutcome(payment, null);
        }
        static CoreOutcome existing(IdempotencyRecord existing) {
            return new CoreOutcome(null, existing);
        }
    }
}

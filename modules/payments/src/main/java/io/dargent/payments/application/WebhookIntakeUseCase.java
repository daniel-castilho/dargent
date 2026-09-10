package io.dargent.payments.application;

import io.dargent.payments.domain.model.EndToEndId;
import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.model.WebhookSignatureValidator;
import io.dargent.payments.domain.port.out.AuditWriter;
import io.dargent.payments.domain.port.out.OutboxWriter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.payments.domain.port.out.WebhookEventRecord;
import io.dargent.payments.domain.port.out.WebhookEventStore;
import io.dargent.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WebhookIntakeUseCase (E4 spec §5.3): single transaction, order fixed.
 * 1. Insert webhook_events RECEIVED (dedupe via provider_event_id unique)
 * 2. Parse payload_raw: unknown type → IGNORED
 * 3. Load payment by txid: unknown → IGNORED
 * 4. Sanity: amount mismatch → IGNORED
 * 5. Confirm via Payment.confirm(endToEndId, FeeBreakdown.of(100bps), paidAt)
 * 6. Append outbox payment.confirmed {amount, fee, net, late:false}
 * 7. Audit log confirm_from_webhook
 * 8. Mark PROCESSED
 */
public final class WebhookIntakeUseCase {

    private static final long FEE_BPS = 100L;
    private static final String BRL = "BRL";
    /**
     * Ratified system actor for PSP callbacks (owner decision 2026-08-30, E3R BD-14).
     * Zero UUID is intentional and greppable — PSP callbacks have no API-key actor;
     * V106's NOT NULL stands; a deterministic system actor keeps forensic queries simple.
     */
    private static final UUID WEBHOOK_AUDIT_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final WebhookEventStore webhookEventStore;
    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final WebhookSignatureValidator signatureValidator;
    private final TransactionTemplate txTemplate;
    private final EventEnvelopeFactory envelopeFactory;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final PaymentsMetrics metrics;

    public WebhookIntakeUseCase(
            WebhookEventStore webhookEventStore,
            PaymentRepository paymentRepository,
            OutboxWriter outboxWriter,
            AuditWriter auditWriter,
            WebhookSignatureValidator signatureValidator,
            TransactionTemplate txTemplate,
            EventEnvelopeFactory envelopeFactory,
            Clock clock,
            ObjectMapper objectMapper,
            PaymentsMetrics metrics) {
        this.webhookEventStore = webhookEventStore;
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.signatureValidator = signatureValidator;
        this.txTemplate = txTemplate;
        this.envelopeFactory = envelopeFactory;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public Outcome execute(Input input) {
        // Step 1: insert webhook_events RECEIVED (durable, dedupe via provider_event_id unique)
        // Runs outside the confirm transaction so the raw row is never lost on rollback —
        // it is the recovery point that lets a reprocess pick up from payload_raw (BD-11).
        WebhookEventRecord eventRecord = new WebhookEventRecord(
                UUID.randomUUID(),
                input.providerEventId(),
                input.pspEventId(),
                input.type(),
                input.txid(),
                input.payloadRaw(),
                input.signatureValid(),
                "RECEIVED",
                clock.instant(),
                null);

        Optional<WebhookEventRecord> existing = webhookEventStore.insertIfAbsent(eventRecord);
        if (existing.isPresent()) {
            WebhookEventRecord prior = existing.get();
            if ("PROCESSED".equals(prior.status())) {
                return Outcome.duplicate();
            }
            if ("RECEIVED".equals(prior.status())) {
                // Reprocess from payload_raw (playbook 10) — the original request id is not
                // recoverable on a re-delivery, so the confirm envelope carries null here.
                return txTemplate.execute(
                        status -> processFromPayload(prior.payloadRaw(), prior.providerEventId(), null));
            }
            return Outcome.ignored("prior status: " + prior.status());
        }

        return txTemplate.execute(
                status -> processFromPayload(input.payloadRaw(), input.providerEventId(), input.requestId()));
    }

    /**
     * M5 S4: admin re-drive of a stored {@code webhook_events} row through the intake core. Dedupe
     * makes this idempotent by design — the {@code provider_event_id} UNIQUE already proved itself on
     * intake, so a re-run of the tool can never double-confirm or double-journal a payment.
     * <ul>
     *   <li>unknown {@code providerEventId} → {@link ReprocessResult.NotFound}</li>
     *   <li>signature-valid=false → {@link ReprocessResult.AttackEvidence}: fail-closed immutable
     *       attack audit (AGENTS §4.4), never re-driven</li>
     *   <li>already PROCESSED → {@link ReprocessResult.AlreadyProcessed} (no-op, idempotent)</li>
     *   <li>RECEIVED or IGNORED → re-run the intake core on the stored (immutable) {@code payload_raw}
     *       — same transaction as a redelivery would, same conditional confirm</li>
     * </ul>
     */
    public ReprocessResult reprocess(String providerEventId) {
        Optional<WebhookEventRecord> row = webhookEventStore.findByProviderEventId(providerEventId);
        if (row.isEmpty()) {
            return ReprocessResult.notFound();
        }
        // Fail-closed: signature-invalid rows are immutable attack evidence (they were persisted on a
        // 401 before any business decision) and must never become the seed of a confirmation.
        if (!row.get().signatureValid()) {
            return ReprocessResult.attackEvidence();
        }
        if ("PROCESSED".equals(row.get().status())) {
            return ReprocessResult.alreadyProcessed();
        }
        // The row's txid (already ≤25 chars by schema) is the money aggregate this re-drive
        // touches — the admin audit records THAT, never the multi-part provider_event_id.
        String rowTxid = row.get().txid();
        // RECEIVED or IGNORED → re-drive from payload_raw. The original request id is not recoverable
        // on an admin re-drive, so the confirm envelope carries null (matches what a re-delivery
        // already does for a RECEIVED row — the money path is identical).
        Outcome outcome =
                txTemplate.execute(status -> processFromPayload(row.get().payloadRaw(), providerEventId, null));
        return switch (outcome) {
            case Outcome.Processed ignored -> ReprocessResult.reProcessed(rowTxid);
            case Outcome.Duplicate ignored -> ReprocessResult.alreadyProcessed();
            case Outcome.Ignored i -> ReprocessResult.ignoredReProcess(i.reason(), rowTxid);
        };
    }

    private Outcome processFromPayload(String payloadRaw, String providerEventId, String requestId) {
        // 2. Parse payload_raw
        ParsedPayload payload;
        try {
            payload = parsePayload(payloadRaw);
        } catch (Exception e) {
            webhookEventStore.markIgnored(providerEventId);
            return Outcome.ignored("parse error: " + e.getMessage());
        }

        // Unknown type
        if (!"payment.confirmed".equals(payload.type())) {
            webhookEventStore.markIgnored(providerEventId);
            return Outcome.ignored("unknown type: " + payload.type());
        }

        // 3. Load payment by txid
        Txid txid;
        try {
            txid = new Txid(payload.txid());
        } catch (IllegalArgumentException e) {
            webhookEventStore.markIgnored(providerEventId);
            return Outcome.ignored("invalid txid: " + payload.txid());
        }

        Optional<Payment> paymentOpt = paymentRepository.findByTxid(txid);
        if (paymentOpt.isEmpty()) {
            webhookEventStore.markIgnored(providerEventId);
            return Outcome.ignored("unknown txid: " + txid.value());
        }
        Payment payment = paymentOpt.get();

        // 4. Sanity: amount mismatch
        if (!payment.amount().equals(Money.of(payload.amount(), BRL))) {
            webhookEventStore.markIgnored(providerEventId);
            return Outcome.ignored("amount mismatch");
        }

        // 5. Confirm
        EndToEndId endToEndId;
        try {
            endToEndId = new EndToEndId(payload.endToEndId());
        } catch (IllegalArgumentException e) {
            webhookEventStore.markIgnored(providerEventId);
            return Outcome.ignored("invalid endToEndId: " + payload.endToEndId());
        }

        Instant paidAt = payload.paidAt();
        FeeBreakdown feeBreakdown =
                FeeBreakdown.of(payment.amount().cents(), new io.dargent.payments.domain.model.BpsRate((int) FEE_BPS));

        int expectedVersion = payment.version();
        PaymentStatus priorStatus = payment.status();
        try {
            payment.confirm(endToEndId, feeBreakdown, paidAt);
        } catch (IllegalArgumentException | io.dargent.payments.domain.exception.InvalidTransitionException e) {
            // Invalid transition or illegal args (e.g., already confirmed) → duplicate
            webhookEventStore.markProcessed(providerEventId);
            return Outcome.duplicate();
        }

        // Persist confirmation inside the same transaction so the atomicity test
        // (BD-11) can roll back confirm + outbox + audit together.
        if (!paymentRepository.updateIfVersionMatches(payment, expectedVersion)) {
            // Race lost (DB row changed under us) → mark PROCESSED and return duplicate
            webhookEventStore.markProcessed(providerEventId);
            return Outcome.duplicate();
        }

        metrics.transition(priorStatus.name(), "CONFIRMED", "webhook_confirm");

        // 6. Append outbox payment.confirmed (full E3 §5.6 envelope; requestId carries the
        //    webhook request's X-Request-Id so end-to-end correlation survives the relay)
        Map<String, Object> outboxPayload = new LinkedHashMap<>();
        outboxPayload.put("amount", payment.amount().cents());
        outboxPayload.put("fee", payment.fee().cents());
        outboxPayload.put("net", payment.net().cents());
        outboxPayload.put("late", false);
        String envelope = envelopeFactory.envelope(
                "payment.confirmed",
                1,
                payment.txid().value(),
                payment.merchantId(),
                requestId,
                outboxPayload,
                clock.instant());
        outboxWriter.append(payment.txid().value(), "payment.confirmed", 1, envelope, requestId);

        // 7. Audit log — webhook has no API key; use sentinel system actor (BD-14)
        auditWriter.record(
                "confirm_from_webhook",
                WEBHOOK_AUDIT_ACTOR,
                payment.merchantId(),
                payment.txid().value(),
                null);

        // 8. Mark PROCESSED
        webhookEventStore.markProcessed(providerEventId);

        return Outcome.processed();
    }

    private ParsedPayload parsePayload(String raw) {
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON payload: " + e.getMessage(), e);
        }

        String type = node.path("type").asText(null);
        String txid = node.path("txid").asText(null);
        String endToEndId = node.path("endToEndId").asText(null);
        JsonNode amountNode = node.path("amount");
        JsonNode paidAtNode = node.path("paidAt");

        if (type == null) throw new IllegalArgumentException("missing field: type");
        if (txid == null) throw new IllegalArgumentException("missing field: txid");
        if (endToEndId == null) throw new IllegalArgumentException("missing field: endToEndId");
        if (amountNode.isMissingNode()) throw new IllegalArgumentException("missing field: amount");
        if (!amountNode.isIntegralNumber()) throw new IllegalArgumentException("amount must be an integer");
        if (paidAtNode.isMissingNode()) throw new IllegalArgumentException("missing field: paidAt");

        String paidAtText = paidAtNode.asText();
        Instant paidAt;
        try {
            paidAt = Instant.parse(paidAtText);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("invalid paidAt format: " + paidAtText, e);
        }

        long amount = node.path("amount").asLong();

        return new ParsedPayload(type, txid, endToEndId, amount, paidAtText, paidAt);
    }

    // ------------------------------------------------------------------ models

    public record Input(
            String providerEventId,
            String pspEventId,
            String type,
            String txid,
            String payloadRaw,
            boolean signatureValid,
            String requestId) {}

    public record ParsedPayload(
            String type, String txid, String endToEndId, long amount, String paidAtText, Instant paidAt) {}

    /**
     * Result of an admin re-drive (M5 S4), distinguished so the HTTP adapter can map each branch
     * honestly: not-found vs attack-evidence (refused, fail-closed) vs idempotent no-op vs a real
     * outcome from the intake core.
     */
    public sealed interface ReprocessResult
            permits ReprocessResult.NotFound,
                    ReprocessResult.AttackEvidence,
                    ReprocessResult.AlreadyProcessed,
                    ReprocessResult.ReProcessed,
                    ReprocessResult.IgnoredReProcess {
        static ReprocessResult notFound() {
            return new NotFound();
        }

        static ReprocessResult attackEvidence() {
            return new AttackEvidence();
        }

        static ReprocessResult alreadyProcessed() {
            return new AlreadyProcessed();
        }

        static ReprocessResult reProcessed(String txid) {
            return new ReProcessed(txid);
        }

        static ReprocessResult ignoredReProcess(String reason, String txid) {
            return new IgnoredReProcess(reason, txid);
        }

        record NotFound() implements ReprocessResult {}

        record AttackEvidence() implements ReprocessResult {}

        record AlreadyProcessed() implements ReprocessResult {}

        /** The re-drive confirmed a payment; {@code txid} is that payment (the money aggregate). */
        record ReProcessed(String txid) implements ReprocessResult {}

        /** The re-drive re-ignored the row; {@code txid} is the row's txid (nullable). */
        record IgnoredReProcess(String reason, String txid) implements ReprocessResult {}
    }

    public sealed interface Outcome permits Outcome.Processed, Outcome.Duplicate, Outcome.Ignored {
        static Outcome processed() {
            return new Processed();
        }

        static Outcome duplicate() {
            return new Duplicate();
        }

        static Outcome ignored(String reason) {
            return new Ignored(reason);
        }

        record Processed() implements Outcome {}

        record Duplicate() implements Outcome {}

        record Ignored(String reason) implements Outcome {}
    }
}

package io.dargent.payments.application;

import io.dargent.payments.domain.model.EndToEndId;
import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
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
    private static final UUID WEBHOOK_AUDIT_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final WebhookEventStore webhookEventStore;
    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final AuditWriter auditWriter;
    private final WebhookSignatureValidator signatureValidator;
    private final TransactionTemplate txTemplate;
    private final EventSerializer eventSerializer;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public WebhookIntakeUseCase(WebhookEventStore webhookEventStore,
            PaymentRepository paymentRepository,
            OutboxWriter outboxWriter,
            AuditWriter auditWriter,
            WebhookSignatureValidator signatureValidator,
            TransactionTemplate txTemplate,
            EventSerializer eventSerializer,
            Clock clock,
            ObjectMapper objectMapper) {
        this.webhookEventStore = webhookEventStore;
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.auditWriter = auditWriter;
        this.signatureValidator = signatureValidator;
        this.txTemplate = txTemplate;
        this.eventSerializer = eventSerializer;
        this.clock = clock;
        this.objectMapper = objectMapper;
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
                null
        );

        Optional<WebhookEventRecord> existing = webhookEventStore.insertIfAbsent(eventRecord);
        if (existing.isPresent()) {
            WebhookEventRecord prior = existing.get();
            if ("PROCESSED".equals(prior.status())) {
                return Outcome.duplicate();
            }
            if ("RECEIVED".equals(prior.status())) {
                // Reprocess from payload_raw (playbook 10)
                return txTemplate.execute(status -> processFromPayload(prior.payloadRaw(), prior.providerEventId()));
            }
            return Outcome.ignored("prior status: " + prior.status());
        }

        return txTemplate.execute(status -> processFromPayload(input.payloadRaw(), input.providerEventId()));
    }

    private Outcome processFromPayload(String payloadRaw, String providerEventId) {
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

        Instant paidAt = Instant.parse(payload.paidAt());
        FeeBreakdown feeBreakdown = FeeBreakdown.of(payment.amount().cents(), new io.dargent.payments.domain.model.BpsRate((int) FEE_BPS));

        int expectedVersion = payment.version();
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

        // 6. Append outbox payment.confirmed
        Map<String, Object> outboxPayload = new LinkedHashMap<>();
        outboxPayload.put("amount", payment.amount().cents());
        outboxPayload.put("fee", payment.fee().cents());
        outboxPayload.put("net", payment.net().cents());
        outboxPayload.put("late", false);
        outboxWriter.append(payment.txid().value(), "payment.confirmed", 1,
                eventSerializer.serialize(outboxPayload), null);

        // 7. Audit log — webhook has no API key, use sentinel actor
        auditWriter.record("confirm_from_webhook", WEBHOOK_AUDIT_ACTOR, payment.merchantId(),
                payment.txid().value(), null);

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

        long amount = amountNode.asLong();
        String paidAt = paidAtNode.asText();

        return new ParsedPayload(type, txid, endToEndId, amount, paidAt);
    }

    // ------------------------------------------------------------------ models

    public record Input(
            String providerEventId,
            String pspEventId,
            String type,
            String txid,
            String payloadRaw,
            boolean signatureValid
    ) {}

    public record ParsedPayload(
            String type,
            String txid,
            String endToEndId,
            long amount,
            String paidAt
    ) {}

    public sealed interface Outcome permits Outcome.Processed, Outcome.Duplicate, Outcome.Ignored {
        static Outcome processed() { return new Processed(); }
        static Outcome duplicate() { return new Duplicate(); }
        static Outcome ignored(String reason) { return new Ignored(reason); }

        record Processed() implements Outcome {}
        record Duplicate() implements Outcome {}
        record Ignored(String reason) implements Outcome {}
    }
}
package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.EntryDirection;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.port.out.LedgerStore;
import io.dargent.shared.events.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event ingestion use case (spec §5.3, §5.4, §5.7).
 * At-least-once + local dedupe by event_id; posting exactly-one-per-event by construction.
 */
public final class EventIngestionUseCase {

    private final EventEnvelopeReader reader;
    private final LedgerStore store;
    private final JdbcClient jdbc;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public EventIngestionUseCase(EventEnvelopeReader reader, LedgerStore store,
            JdbcClient jdbc, TransactionTemplate txTemplate, Clock clock) {
        this.reader = reader;
        this.store = store;
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * Processes one SQS message (runOnce semantics).
     * Returns true if message was processed (ack), false if poison (nack).
     */
    public boolean processMessage(String rawBody) {
        EventEnvelope envelope;
        try {
            envelope = reader.read(rawBody);
        } catch (IllegalArgumentException e) {
            // Poison: invalid JSON or envelope structure — do NOT ack
            return false;
        }

        // 1) Dedupe: insert event if absent (ON CONFLICT DO NOTHING)
        boolean inserted = store.insertEventIfAbsent(
                envelope.eventId(),
                envelope.type(),
                envelope.aggregateId(),
                envelope.merchantId(),
                envelope.payloadJson(),
                "RECEIVED",
                "Initial receipt"
        );

        if (!inserted) {
            // Duplicate delivery — ack + skip
            return true;
        }

        // 2) Non-confirmed events: IGNORED, no postings
        if (!"payment.confirmed".equals(envelope.type())) {
            store.insertEventIfAbsent(
                    envelope.eventId(), envelope.type(), envelope.aggregateId(),
                    envelope.merchantId(), envelope.payloadJson(),
                    "IGNORED", "Non-confirmed type: " + envelope.type()
            );
            return true;
        }

        // 3) Parse payment payload with validation
        EventEnvelopeReader.PaymentPayload payload;
        try {
            payload = reader.extractPaymentPayload(envelope);
        } catch (IllegalArgumentException e) {
            // Invariant violation — REJECTED, no postings
            store.insertEventIfAbsent(
                    envelope.eventId(), envelope.type(), envelope.aggregateId(),
                    envelope.merchantId(), envelope.payloadJson(),
                    "REJECTED", e.getMessage()
            );
            return true;
        }

        // 4) Single transaction: update event status, journal + postings + balances
        return txTemplate.execute(status -> {
            // Update event to POSTED
            jdbc.sql("""
                    UPDATE ledger.events
                    SET status = 'POSTED', note = 'Posted successfully'
                    WHERE event_id = ?
                    """)
                    .param(envelope.eventId())
                    .update();

            // Build postings (spec §5.3)
            Instant now = clock.instant();
            UUID entryId = UUID.randomUUID();
            var postings = List.of(
                    new Posting(UUID.randomUUID(), entryId, "payments:processing",
                            EntryDirection.DEBIT, payload.amountCents(), clock.instant()),
                    new Posting(UUID.randomUUID(), entryId, "fees:revenue",
                            EntryDirection.CREDIT, payload.feeCents(), clock.instant()),
                    new Posting(UUID.randomUUID(), entryId,
                            "merchant:" + payload.merchantId() + ":available",
                            EntryDirection.CREDIT, payload.netCents(), clock.instant())
            );

            var entry = new JournalEntry(
                    UUID.randomUUID(),
                    envelope.eventId(),
                    payload.txid(),
                    UUID.fromString(payload.merchantId()),
                    "Payment confirmed: " + payload.txid(),
                    envelope.occurredAt(),
                    postings
            );

            // Write journal + postings + balances
            store.postJournal(entry);
            return true;
        });
    }
}
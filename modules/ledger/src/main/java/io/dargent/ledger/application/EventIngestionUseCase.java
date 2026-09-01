package io.dargent.ledger.application;

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
     *
     * <p>The terminal status is decided before the single idempotent insert so the event row carries
     * its true state from birth: non-confirmed types are IGNORED, invalid confirmed payloads are
     * REJECTED (validated at the boundary), and valid confirmed events are RECEIVED and then
     * transitioned to POSTED with the journal. A single insert avoids the silent ON CONFLICT no-op
     * that would otherwise freeze the row in RECEIVED (§5.3, §5.7).
     */
    public boolean processMessage(String rawBody) {
        EventEnvelope envelope;
        try {
            envelope = reader.read(rawBody);
        } catch (IllegalArgumentException e) {
            // Poison: invalid JSON or envelope structure — do NOT ack
            return false;
        }

        String status;
        String note;
        EventEnvelopeReader.PaymentPayload payload = null;
        if (!"payment.confirmed".equals(envelope.type())) {
            // Non-posting event: terminal state is IGNORED from birth.
            status = "IGNORED";
            note = "Non-confirmed type: " + envelope.type();
        } else {
            try {
                payload = reader.extractPaymentPayload(envelope);
                status = "RECEIVED";
                note = "Initial receipt";
            } catch (IllegalArgumentException e) {
                // Invariant violation — REJECTED at the boundary, no postings.
                status = "REJECTED";
                note = e.getMessage();
            }
        }

        // Single idempotent insert; a duplicate (event_id already present) is an at-least-once retry.
        boolean inserted = store.insertEventIfAbsent(
                envelope.eventId(), envelope.type(), envelope.aggregateId(),
                envelope.merchantId(), envelope.payload(), status, note);

        if (!inserted) {
            // Duplicate delivery — ack + skip
            return true;
        }

        final EventEnvelopeReader.PaymentPayload postedPayload = payload;
        if (postedPayload == null) {
            // IGNORED or REJECTED — no postings.
            return true;
        }

        // Single transaction: update event status, journal + postings + balances
        return txTemplate.execute(txStatus -> {
            // Update event to POSTED
            jdbc.sql("""
                    UPDATE ledger.events
                    SET status = 'POSTED', note = 'Posted successfully'
                    WHERE event_id = ?
                    """)
                    .param(envelope.eventId())
                    .update();

            // Build postings (spec §5.3)
            UUID entryId = UUID.randomUUID();
            var postings = List.of(
                    new Posting(UUID.randomUUID(), entryId, "payments:processing",
                            EntryDirection.DEBIT, postedPayload.amountCents(), clock.instant()),
                    new Posting(UUID.randomUUID(), entryId, "fees:revenue",
                            EntryDirection.CREDIT, postedPayload.feeCents(), clock.instant()),
                    new Posting(UUID.randomUUID(), entryId,
                            "merchant:" + postedPayload.merchantId() + ":available",
                            EntryDirection.CREDIT, postedPayload.netCents(), clock.instant())
            );

            var entry = new JournalEntry(
                    entryId,
                    envelope.eventId(),
                    postedPayload.txid(),
                    UUID.fromString(postedPayload.merchantId()),
                    "Payment confirmed: " + postedPayload.txid(),
                    envelope.occurredAt(),
                    postings
            );

            // Write journal + postings + balances
            store.postJournal(entry);
            return true;
        });
    }
}
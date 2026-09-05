package io.dargent.ledger.application;

import io.dargent.ledger.application.EventEnvelopeReader;
import io.dargent.ledger.domain.model.EntryDirection;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.port.out.LedgerStore;
import io.dargent.shared.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Event ingestion use case (spec §5.3, §5.4, §5.7).
 * At-least-once + local dedupe by event_id; posting exactly-one-per-event by construction.
 */
public final class EventIngestionUseCase {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionUseCase.class);

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

        log.atInfo()
                .setMessage("LEDGER ingest")
                .addKeyValue("request_id", envelope.requestId())
                .addKeyValue("event_id", envelope.eventId().toString())
                .addKeyValue("type", envelope.type())
                .addKeyValue("aggregate_id", envelope.aggregateId())
                .addKeyValue("merchant_id", envelope.merchantId().toString())
                .log();

        String status;
        String note;
        EventEnvelopeReader.PaymentPayload paymentPayload = null;
        EventEnvelopeReader.RefundPayload refundPayload = null;
        if ("payment.confirmed".equals(envelope.type())) {
            try {
                paymentPayload = reader.extractPaymentPayload(envelope);
                status = "RECEIVED";
                note = "Initial receipt";
            } catch (IllegalArgumentException e) {
                // Invariant violation — REJECTED at the boundary, no postings.
                status = "REJECTED";
                note = e.getMessage();
            }
        } else if ("refund.created".equals(envelope.type())) {
            try {
                refundPayload = reader.extractRefundPayload(envelope);
                status = "RECEIVED";
                note = "Initial receipt";
            } catch (IllegalArgumentException e) {
                status = "REJECTED";
                note = e.getMessage();
            }
        } else {
            // Non-posting event: terminal state is IGNORED from birth.
            status = "IGNORED";
            note = "Non-posting type: " + envelope.type();
        }

        // Single idempotent insert; a duplicate (event_id already present) is an at-least-once retry.
        boolean inserted = store.insertEventIfAbsent(
                envelope.eventId(), envelope.type(), envelope.aggregateId(),
                envelope.merchantId(), envelope.payload(), status, note);

        if (!inserted) {
            // Duplicate delivery — re-read stored status and branch
            String storedStatus = store.findEventStatus(envelope.eventId())
                    .orElseThrow(() -> new IllegalStateException("Event " + envelope.eventId() + " not found"));

            if ("RECEIVED".equals(storedStatus)) {
                // Resume posting in ONE transaction: claim + journal + postings + balances
                return resumePosting(envelope, paymentPayload, refundPayload);
            }
            // POSTED / IGNORED / REJECTED → ack + skip
            return true;
        }

        // payment.confirmed path
        if (paymentPayload != null) {
            final var finalPaymentPayload = paymentPayload;
            final var finalEnvelope = envelope;
            final var finalClock = clock;
            return txTemplate.execute(txStatus -> {
                // Scenario 20 / E9 §6.4: prevent double-journaling of republished events.
                // If a POSTED journal entry already exists for this txid, the event is a
                // republish — mark as POSTED but skip journal creation.
                if (store.hasPostedJournalForTxid(finalPaymentPayload.txid())) {
                    jdbc.sql("""
                            UPDATE ledger.events
                            SET status = 'POSTED', note = 'Republished — already journaled'
                            WHERE event_id = ?
                            """)
                            .param(finalEnvelope.eventId())
                            .update();
                    return true;
                }

                // Update event to POSTED
                jdbc.sql("""
                        UPDATE ledger.events
                        SET status = 'POSTED', note = 'Posted successfully'
                        WHERE event_id = ?
                        """)
                        .param(finalEnvelope.eventId())
                        .update();

                // Build postings (spec §5.3)
                UUID entryId = UUID.randomUUID();
                var postings = List.of(
                        new Posting(UUID.randomUUID(), entryId, "payments:processing",
                                EntryDirection.DEBIT, finalPaymentPayload.amountCents(), finalClock.instant()),
                        new Posting(UUID.randomUUID(), entryId, "fees:revenue",
                                EntryDirection.CREDIT, finalPaymentPayload.feeCents(), finalClock.instant()),
                        new Posting(UUID.randomUUID(), entryId,
                                "merchant:" + finalPaymentPayload.merchantId() + ":available",
                                EntryDirection.CREDIT, finalPaymentPayload.netCents(), finalClock.instant())
                );

                var entry = new JournalEntry(
                        entryId,
                        finalEnvelope.eventId(),
                        finalPaymentPayload.txid(),
                        UUID.fromString(finalPaymentPayload.merchantId()),
                        "Payment confirmed: " + finalPaymentPayload.txid(),
                        finalEnvelope.occurredAt(),
                        postings
                );

                // Write journal + postings + balances
                store.postJournal(entry);
                return true;
            });
        }

        // refund.created path
        if (refundPayload != null) {
            final var finalRefundPayload = refundPayload;
            final var finalEnvelope = envelope;
            final var finalClock = clock;
            return txTemplate.execute(txStatus -> {
                boolean posted = store.postRefund(
                        finalEnvelope.eventId(),
                        finalRefundPayload.txid(),
                        UUID.fromString(finalRefundPayload.merchantId()),
                        finalRefundPayload.amountCents(),
                        finalRefundPayload.feeReversalCents(),
                        "Refund: " + finalRefundPayload.txid(),
                        finalEnvelope.occurredAt(),
                        finalClock);
                // Update event status based on result
                String finalStatus = posted ? "POSTED" : "IGNORED";
                String finalNote = posted ? "Posted successfully" : "Insufficient merchant balance";
                jdbc.sql("""
                        UPDATE ledger.events
                        SET status = ?, note = ?
                        WHERE event_id = ?
                        """)
                        .param(finalStatus)
                        .param(finalNote)
                        .param(envelope.eventId())
                        .update();
                return true;
            });
        }

        // IGNORED or REJECTED — no postings.
        return true;
    }

    /**
     * Resumes posting for an event that was left in RECEIVED state (e.g., consumer crashed after
     * insert but before journal write). Runs in a single transaction: conditional claim of the
     * event row (UPDATE ... WHERE status = 'RECEIVED'), then journal + postings + balances.
     * Belt-and-suspenders: if journal insert still collides (UNIQUE on journal_entries.event_id),
     * re-read status and ack as already-posted.
     */
    private boolean resumePosting(EventEnvelope envelope,
            EventEnvelopeReader.PaymentPayload paymentPayload,
            EventEnvelopeReader.RefundPayload refundPayload) {
        // payment.confirmed resume
        if (paymentPayload != null) {
            final var finalPaymentPayload = paymentPayload;
            final var finalEnvelope = envelope;
            final var finalClock = clock;
            return txTemplate.execute(txStatus -> {
                // Conditional claim: only this consumer wins the race
                int claimed = store.claimEventForResume(finalEnvelope.eventId());
                if (claimed == 0) {
                    // Another consumer won the resume — ack + skip, zero writes
                    return true;
                }

                try {
                    // Build postings (spec §5.3)
                    UUID entryId = UUID.randomUUID();
                    var postings = List.of(
                            new Posting(UUID.randomUUID(), entryId, "payments:processing",
                                    EntryDirection.DEBIT, finalPaymentPayload.amountCents(), finalClock.instant()),
                            new Posting(UUID.randomUUID(), entryId, "fees:revenue",
                                    EntryDirection.CREDIT, finalPaymentPayload.feeCents(), finalClock.instant()),
                            new Posting(UUID.randomUUID(), entryId,
                                    "merchant:" + finalPaymentPayload.merchantId() + ":available",
                                    EntryDirection.CREDIT, finalPaymentPayload.netCents(), finalClock.instant())
                    );

                    var entry = new JournalEntry(
                            entryId,
                            finalEnvelope.eventId(),
                            finalPaymentPayload.txid(),
                            UUID.fromString(finalPaymentPayload.merchantId()),
                            "Payment confirmed: " + finalPaymentPayload.txid(),
                            finalEnvelope.occurredAt(),
                            postings
                    );

                    // Write journal + postings + balances
                    store.postJournal(entry);
                    return true;
                } catch (DataIntegrityViolationException e) {
                    // Belt-and-suspenders: journal_entries.event_id UNIQUE collision
                    // Re-read status; if POSTED, ack as already-posted; else rethrow
                    String status = store.findEventStatus(finalEnvelope.eventId())
                            .orElseThrow(() -> new IllegalStateException("Event " + finalEnvelope.eventId() + " vanished"));
                    if ("POSTED".equals(status)) {
                        return true;
                    }
                    throw e;
                }
            });
        }

        // refund.created resume
        if (refundPayload != null) {
            final var finalRefundPayload = refundPayload;
            final var finalEnvelope = envelope;
            final var finalClock = clock;
            return txTemplate.execute(txStatus -> {
                int claimed = store.claimEventForResume(finalEnvelope.eventId());
                if (claimed == 0) {
                    return true;
                }

                boolean posted = store.postRefund(
                        finalEnvelope.eventId(),
                        finalRefundPayload.txid(),
                        UUID.fromString(finalRefundPayload.merchantId()),
                        finalRefundPayload.amountCents(),
                        finalRefundPayload.feeReversalCents(),
                        "Refund: " + finalRefundPayload.txid(),
                        finalEnvelope.occurredAt(),
                        finalClock);
                String finalStatus = posted ? "POSTED" : "IGNORED";
                String finalNote = posted ? "Posted successfully" : "Insufficient merchant balance";
                jdbc.sql("""
                        UPDATE ledger.events
                        SET status = ?, note = ?
                        WHERE event_id = ?
                        """)
                        .param(finalStatus)
                        .param(finalNote)
                        .param(envelope.eventId())
                        .update();
                return true;
            });
        }

        return true;
    }
}
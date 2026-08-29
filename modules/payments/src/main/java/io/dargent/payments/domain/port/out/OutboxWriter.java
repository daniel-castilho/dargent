package io.dargent.payments.domain.port.out;

/** Outbox writer port (E3 spec §5.6, design.md §7.4): appends an event to the transactional outbox. */
public interface OutboxWriter {

    /** Appends an event to the outbox in the current transaction. */
    void append(String aggregateId, String type, int version, String payloadJson, String requestId);
}
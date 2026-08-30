package io.dargent.payments.domain.port.out;

/**
 * Port for publishing events to the messaging backbone (E6 §5.2).
 * Implemented by the SNS adapter (S3).
 */
public interface EventPublisher {

    /**
     * Publishes an event to the SNS FIFO topic.
     *
     * @param type         event type (e.g., "payment.confirmed")
     * @param payload      stored envelope payload verbatim (jsonb text)
     * @param eventId      envelope eventId (MessageDeduplicationId)
     * @param aggregateId  payment txid (MessageGroupId)
     */
    void publish(String type, String payload, String eventId, String aggregateId);
}
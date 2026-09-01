package io.dargent.ledger.adapter.out.messaging;

import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.shared.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SQS consumer for the ledger fan-out queue (spec §5.1, §5.3).
 * Polls the ledger FIFO queue, delegates to {@link EventIngestionUseCase},
 * acks only committed work; never acks poison messages (they redrive to DLQ after 5 receives).
 * A plain POJO wired explicitly by the boot app's composition root (no component scan — the
 * module owns no Spring annotation coupling; AGENTS §2.2, E7 spec §7 hygiene).
 */
public class SqsEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsEventConsumer.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final int batchSize;
    private final long pollMs;
    private final EventIngestionUseCase ingestion;

    public SqsEventConsumer(
            SqsClient sqs,
            String queueUrl,
            int batchSize,
            long pollMs,
            EventIngestionUseCase ingestion) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.batchSize = Math.min(batchSize, 10); // SQS max batch = 10
        this.pollMs = pollMs;
        this.ingestion = ingestion;
    }

    /**
     * Runs one consumer cycle: receive up to batchSize messages, process each,
     * ack only those successfully processed; poison messages (ingestion returns false)
     * are left in the queue to be redriven to DLQ after 5 receives.
     *
     * @return number of messages successfully processed
     */
    public int runOnce() {
        var request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(batchSize)
                .waitTimeSeconds(20) // long poll
                .build();

        List<Message> messages = sqs.receiveMessage(request).messages();
        if (messages.isEmpty()) {
            return 0;
        }

        int processed = 0;
        var deleteEntries = new java.util.ArrayList<DeleteMessageBatchRequestEntry>();

        for (Message msg : messages) {
            String body = msg.body();
            String receiptHandle = msg.receiptHandle();

            boolean acked;
            try {
                acked = ingestion.processMessage(body);
            } catch (Exception e) {
                log.error("Ledger ingestion failed for message {}: {}", msg.messageId(), e.getMessage(), e);
                acked = false;
            }

            if (acked) {
                deleteEntries.add(DeleteMessageBatchRequestEntry.builder()
                        .id(msg.messageId())
                        .receiptHandle(receiptHandle)
                        .build());
                processed++;
            } else {
                log.warn("Message {} not acked (poison or processing error); will redrive after 5 receives", msg.messageId());
            }
        }

        if (!deleteEntries.isEmpty()) {
            sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(deleteEntries)
                    .build());
        }

        return processed;
    }

    /**
     * Runs continuous polling loop. Respects {@link #pollMs} between empty receives.
     * Caller should run this in a scheduler thread.
     */
    public void runContinuous() {
        while (!Thread.currentThread().isInterrupted()) {
            int processed = runOnce();
            if (processed == 0) {
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
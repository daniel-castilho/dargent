package io.dargent.notifications.adapter.out.messaging;

import io.dargent.notifications.application.NotificationIngestionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsNotificationConsumerTest {

    @Mock
    SqsClient sqsClient;

    @Mock
    NotificationIngestionUseCase ingestion;

    @Test
    void ingestion_returns_false_message_not_acked_stays_for_redelivery() {
        // Given: a message that will be rejected (poison)
        Message poisonMsg = Message.builder()
                .messageId("poison-1")
                .receiptHandle("rh-poison-1")
                .body("{\"eventId\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"payment.confirmed\",\"version\":1,\"aggregateId\":\"txid\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"occurredAt\":\"2026-08-30T12:00:00Z\",\"payload\":\"not valid json\"}")
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(poisonMsg)).build());
        when(ingestion.processMessage(anyString())).thenReturn(false); // poison -> nack

        SqsNotificationConsumer consumer = new SqsNotificationConsumer(sqsClient, "http://queue", 10, 1000, ingestion);

        // When
        int processed = consumer.runOnce();

        // Then: processed = 0 (no ack), deleteMessageBatch NOT called for poison message
        assertThat(processed).isZero();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void ingestion_returns_true_message_acked_and_deleted() {
        // Given: a valid message that will be processed successfully
        Message validMsg = Message.builder()
                .messageId("valid-1")
                .receiptHandle("rh-valid-1")
                .body("{\"eventId\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"payment.confirmed\",\"version\":1,\"aggregateId\":\"txid\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"occurredAt\":\"2026-08-30T12:00:00Z\",\"payload\":{\"txid\":\"txid\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}}")
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(validMsg)).build());
        when(ingestion.processMessage(anyString())).thenReturn(true); // success -> ack

        SqsNotificationConsumer consumer = new SqsNotificationConsumer(sqsClient, "http://queue", 10, 1000, ingestion);

        // When
        int processed = consumer.runOnce();

        // Then: processed = 1, deleteMessageBatch called with the message
        assertThat(processed).isEqualTo(1);
        verify(sqsClient).deleteMessageBatch(argThat((DeleteMessageBatchRequest req) ->
                req.entries().size() == 1 &&
                req.entries().get(0).id().equals("valid-1") &&
                req.entries().get(0).receiptHandle().equals("rh-valid-1")));
    }

    @Test
    void mixed_poison_and_valid_only_valid_acked() {
        // Given: one poison, one valid
        Message poisonMsg = Message.builder()
                .messageId("poison-1")
                .receiptHandle("rh-poison-1")
                .body("invalid")
                .build();
        Message validMsg = Message.builder()
                .messageId("valid-1")
                .receiptHandle("rh-valid-1")
                .body("{\"eventId\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"payment.confirmed\",\"version\":1,\"aggregateId\":\"txid\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"occurredAt\":\"2026-08-30T12:00:00Z\",\"payload\":{\"txid\":\"txid\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}}")
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(poisonMsg, validMsg)).build());
        when(ingestion.processMessage("invalid")).thenReturn(false);
        when(ingestion.processMessage(contains("txid"))).thenReturn(true);

        SqsNotificationConsumer consumer = new SqsNotificationConsumer(sqsClient, "http://queue", 10, 1000, ingestion);

        // When
        int processed = consumer.runOnce();

        // Then: only valid message acked and deleted
        assertThat(processed).isEqualTo(1);
        verify(sqsClient).deleteMessageBatch(argThat((DeleteMessageBatchRequest req) ->
                req.entries().size() == 1 &&
                req.entries().get(0).id().equals("valid-1")));
    }
}
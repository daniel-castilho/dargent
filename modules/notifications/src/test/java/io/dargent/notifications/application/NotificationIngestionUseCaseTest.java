package io.dargent.notifications.application;

import io.dargent.notifications.domain.port.out.NotificationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationIngestionUseCaseTest {

    private NotificationIngestionUseCase useCase;
    private FakeNotificationStore store;
    private Clock fixedClock;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        store = new FakeNotificationStore();
        fixedClock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), java.time.ZoneOffset.UTC);
        var reader = new EventEnvelopeReader();
        jdbc = mock(JdbcClient.class);
        var spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        useCase = new NotificationIngestionUseCase(new EventEnvelopeReader(), store, jdbc, fixedClock);
    }

    @Test
    void processes_valid_notification_and_acks() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedNotifications).hasSize(1);
    }

    @Test
    void duplicate_notification_is_acked_and_skipped_zero_writes() {
        String raw = rawEnvelope("payment.confirmed",
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}");

        boolean ack1 = useCase.processMessage(raw);
        boolean ack2 = useCase.processMessage(raw);

        assertThat(ack1).isTrue();
        assertThat(ack2).isTrue();
        assertThat(store.insertedNotifications).hasSize(1); // zero new writes on duplicate
    }

    @Test
    void non_posting_event_is_recorded_and_acked() {
        String raw = rawEnvelope("payment.created", "{}");

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isTrue();
        assertThat(store.insertedNotifications).hasSize(1);
        var eventId = store.insertedNotifications.keySet().iterator().next();
        assertThat(store.insertedNotifications.get(eventId).type()).isEqualTo("payment.created");
    }

    @Test
    void malformed_envelope_returns_false_no_row() {
        String raw = "not valid json";

        boolean ack = useCase.processMessage(raw);

        assertThat(ack).isFalse();
        assertThat(store.insertedNotifications).isEmpty();
    }

    @Test
    void every_event_type_records() {
        for (String type : java.util.List.of("payment.created", "payment.failed", "payment.confirmed", "payment.expired")) {
            String raw = rawEnvelope(type, "{}");
            boolean ack = useCase.processMessage(raw);
            assertThat(ack).isTrue();
        }
        assertThat(store.insertedNotifications).hasSize(4);
    }

    private static String rawEnvelope(String type, String payloadJson) {
        return "{\"eventId\":\"" + java.util.UUID.randomUUID()
                + "\",\"type\":\"" + type
                + "\",\"version\":1,\"aggregateId\":\"txid-123\""
                + ",\"merchantId\":\"11111111-1111-1111-1111-111111111111\""
                + ",\"requestId\":\"req-123\",\"occurredAt\":\"2026-08-30T12:00:00Z\""
                + ",\"payload\":" + payloadJson + "}";
    }

    // Fake NotificationStore for testing
    static class FakeNotificationStore implements NotificationStore {
        final ConcurrentHashMap<UUID, NotificationRecord> insertedNotifications = new ConcurrentHashMap<>();

        record NotificationRecord(UUID eventId, String type, String txid, UUID merchantId,
                                  String payload, Instant occurredAt) {}

        @Override
        public boolean insertNotificationIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
                String payload, Instant occurredAt) {
            if (insertedNotifications.containsKey(eventId)) {
                return false;
            }
            NotificationRecord record = new NotificationRecord(eventId, type, txid, merchantId, payload, occurredAt);
            insertedNotifications.put(eventId, record);
            return true;
        }
    }
}
package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.port.out.WebhookEventRecord;
import io.dargent.payments.domain.port.out.WebhookEventStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the webhook event store (E4 spec §5.4). */
@Repository
public class JdbcWebhookEventStore implements WebhookEventStore {

    private final JdbcClient jdbc;

    public JdbcWebhookEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WebhookEventRecord> insertIfAbsent(WebhookEventRecord record) {
        // On fresh insert, RETURNING yields the new row; on conflict, yields NO rows.
        // Contract (WebhookIntakeUseCase): empty => we inserted (won the race); present =>
        // a row already existed (duplicate webhook). So a row returned by the insert means
        // "we inserted" => return empty; a conflict means "already there" => re-read and return it.
        Optional<WebhookEventRecord> inserted = jdbc.sql("""
                insert into payments.webhook_events (
                    id, provider_event_id, psp_event_id, type, txid, payload_raw, signature_valid, status
                )                 values (
                    :id, :providerEventId, :pspEventId, :type, :txid, :payloadRaw::jsonb, :signatureValid, :status
                )
                on conflict (provider_event_id) do nothing
                returning id, provider_event_id, psp_event_id, type, txid, payload_raw, signature_valid, status, received_at, processed_at
                """)
                .param("id", record.id())
                .param("providerEventId", record.providerEventId())
                .param("pspEventId", record.pspEventId())
                .param("type", record.type())
                .param("txid", record.txid())
                .param("payloadRaw", record.payloadRaw())
                .param("signatureValid", record.signatureValid())
                .param("status", record.status())
                .query(WebhookEventRecord.class)
                .optional();
        if (inserted.isPresent()) {
            return Optional.empty(); // we own the newly inserted row
        }
        return jdbc.sql("""
                select id, provider_event_id, psp_event_id, type, txid, payload_raw, signature_valid, status, received_at, processed_at
                from payments.webhook_events
                where provider_event_id = :providerEventId
                """)
                .param("providerEventId", record.providerEventId())
                .query(WebhookEventRecord.class)
                .optional();
    }

    @Override
    public void markProcessed(String providerEventId) {
        jdbc.sql("""
                update payments.webhook_events
                set status = 'PROCESSED', processed_at = now()
                where provider_event_id = :id
                """)
                .param("id", providerEventId)
                .update();
    }

    @Override
    public void markIgnored(String providerEventId) {
        jdbc.sql("""
                update payments.webhook_events
                set status = 'IGNORED', processed_at = now()
                where provider_event_id = :id
                """)
                .param("id", providerEventId)
                .update();
    }

    @Override
    public Optional<WebhookEventRecord> findByProviderEventId(String providerEventId) {
        return jdbc.sql("""
                select id, provider_event_id, psp_event_id, type, txid, payload_raw, signature_valid, status, received_at, processed_at
                from payments.webhook_events
                where provider_event_id = :id
                """)
                .param("id", providerEventId)
                .query(WebhookEventRecord.class)
                .optional();
    }
}
package io.dargent.payments.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/** Read-only record matching the webhook_events table (E4 spec §5.4). */
public record WebhookEventRecord(
        UUID id,
        String providerEventId,
        String pspEventId,
        String type,
        String txid,
        String payloadRaw,
        boolean signatureValid,
        String status,
        Instant receivedAt,
        Instant processedAt
) {}
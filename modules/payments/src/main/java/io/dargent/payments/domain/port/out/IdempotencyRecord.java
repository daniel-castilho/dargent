package io.dargent.payments.domain.port.out;

import java.util.Map;
import java.util.UUID;

/** Read-only record matching the idempotency_keys table. */
public record IdempotencyRecord(
        UUID merchantId,
        String idempotencyKey,
        String endpoint,
        String requestFingerprint,
        String state,
        String paymentTxid,
        Integer responseStatus,
        Map<String, Object> responseBody
) {}
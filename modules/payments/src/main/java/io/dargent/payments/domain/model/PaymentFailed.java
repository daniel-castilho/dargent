package io.dargent.payments.domain.model;

import java.time.Instant;

/**
 * Raised when a newborn payment fails — PSP unavailable after creation retries
 * exhausted (D19). The reason lives in the event only.
 */
public record PaymentFailed(Txid txid, String reason, Instant occurredAt) implements PaymentEvent {
}
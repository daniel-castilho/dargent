package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.Txid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-side port for payment queries (E3 spec §5.2): detail by txid + cursor pagination. */
public interface PaymentQueryPort {

    /** Finds a payment by txid (tenant-scoped — returns empty if another merchant's). */
    Optional<Payment> findByTxid(UUID merchantId, Txid txid);

    /**
     * Lists payments for a merchant with cursor pagination.
     * Returns up to limit items, ordered by created_at DESC, txid DESC (stable under insertion).
     * Cursor is opaque base64(txId|createdAtMicros).
     */
    List<Payment> findPage(UUID merchantId, String cursor, int limit);
}
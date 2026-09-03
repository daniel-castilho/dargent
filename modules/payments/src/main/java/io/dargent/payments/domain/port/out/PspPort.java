package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;
import java.time.Instant;

/** Port for PSP communication (E3 spec §5.7, design.md §5.9): only the adapter knows the simulator. */
public interface PspPort {

    /** Creates a PIX charge at the PSP. Retries are handled by the adapter. */
    ChargeResult createCharge(CreateChargeInput input);

    /** Queries the PSP for the current state of a charge (E5 spec §4). */
    CobStatus getCob(Txid txid);

    /** Input for creating a PIX charge. */
    record CreateChargeInput(
            Txid txid,
            long amountCents,
            Instant expiresAt,
            String callbackUrl,
            String description
    ) {}

    /** Result of a successful charge creation. */
    record ChargeResult(
            Txid txid,
            Instant expiresAt,
            String endToEndId,
            String brcodePayload
    ) {}

    /** PSP charge status response (E2 truth endpoint GET /cobs/{txid}). */
    record CobStatus(
            Txid txid,
            CobState state,
            long amountCents,
            Instant expiresAt,
            String endToEndId,
            Instant paidAt
    ) {}

    /** PSP charge states. */
    enum CobState {
        OPEN,
        PAID,
        EXPIRED
    }
}
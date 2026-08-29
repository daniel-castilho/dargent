package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;
import java.time.Instant;

/** Port for PSP communication (E3 spec §5.7, design.md §5.9): only the adapter knows the simulator. */
public interface PspPort {

    /** Creates a PIX charge at the PSP. Retries are handled by the adapter. */
    ChargeResult createCharge(CreateChargeInput input);

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
}
package io.dargent.payments.adapter.out.psp;

import io.dargent.payments.domain.br.BrCode;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PaymentRail;
import io.dargent.payments.domain.port.out.PspPort;

/**
 * PIX rail (M5 S0): the PIX payment strategy behind the {@link PaymentRail} seam. Delegates the
 * shared PSP charge/truth contract byte-for-byte to the low-level HTTP adapter and owns the PIX
 * presentment (EMV BR Code composed from the configured receiver profile) that previously lived in
 * the create use case. No business rules — strategy wiring only, so delegation keeps PIX behaviour
 * identical to before the extraction.
 */
public final class PixRail implements PaymentRail {

    public static final String RAIL = "pix";

    private final PspPort chargePort;
    private final String pixKey;
    private final String receiverName;
    private final String receiverCity;

    public PixRail(PspPort chargePort, String pixKey, String receiverName, String receiverCity) {
        this.chargePort = chargePort;
        this.pixKey = pixKey;
        this.receiverName = receiverName;
        this.receiverCity = receiverCity;
    }

    @Override
    public ChargeResult createCharge(CreateChargeInput input) {
        return chargePort.createCharge(input);
    }

    @Override
    public CobStatus getCob(Txid txid) {
        return chargePort.getCob(txid);
    }

    @Override
    public String rail() {
        return RAIL;
    }

    @Override
    public String presentment(Txid txid, long amountCents) {
        return BrCode.of(pixKey, receiverName, receiverCity, amountCents, txid);
    }
}

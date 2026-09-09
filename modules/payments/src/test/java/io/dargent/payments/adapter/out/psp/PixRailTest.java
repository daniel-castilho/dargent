package io.dargent.payments.adapter.out.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dargent.payments.domain.br.BrCode;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PspPort;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * PixRail contract (M5 S0): the PIX strategy delegates the shared PSP charge/truth contract
 * byte-for-byte to the low-level adapter and owns the EMV presentment. No business rules — the
 * extraction is proven by equality with {@link BrCode} and by delegation.
 */
class PixRailTest {

    private static final String PIX_KEY = "dargent-dev-receber@example.com";
    private static final String RECEIVER_NAME = "Dargent Dev LTDA";
    private static final String RECEIVER_CITY = "SAO PAULO";
    private static final Txid TXID = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-30T10:30:00Z");

    private final PspPort chargePort = mock(PspPort.class);
    private final PixRail rail = new PixRail(chargePort, PIX_KEY, RECEIVER_NAME, RECEIVER_CITY);

    @Test
    void rail_is_pix() {
        assertThat(rail.rail()).isEqualTo("pix");
    }

    @Test
    void presentment_is_the_emv_br_code_for_the_configured_profile() {
        assertThat(rail.presentment(TXID, 10_000))
                .isEqualTo(BrCode.of(PIX_KEY, RECEIVER_NAME, RECEIVER_CITY, 10_000, TXID));
    }

    @Test
    void create_charge_delegates_to_the_low_level_adapter() {
        PspPort.CreateChargeInput input = new PspPort.CreateChargeInput(TXID, 10_000, EXPIRES_AT, "cb", "desc");
        PspPort.ChargeResult expected = new PspPort.ChargeResult(TXID, EXPIRES_AT, "E2E-1", "br");
        when(chargePort.createCharge(input)).thenReturn(expected);

        assertThat(rail.createCharge(input)).isEqualTo(expected);
        verify(chargePort).createCharge(input);
    }

    @Test
    void get_cob_delegates_to_the_low_level_adapter() {
        PspPort.CobStatus expected =
                new PspPort.CobStatus(TXID, PspPort.CobState.PAID, 10_000, EXPIRES_AT, "E2E-1", EXPIRES_AT);
        when(chargePort.getCob(TXID)).thenReturn(expected);

        assertThat(rail.getCob(TXID)).isEqualTo(expected);
        verify(chargePort).getCob(TXID);
    }
}

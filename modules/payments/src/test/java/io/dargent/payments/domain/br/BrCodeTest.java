package io.dargent.payments.domain.br;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.payments.domain.model.Txid;
import org.junit.jupiter.api.Test;

/**
 * BR Code composer contract (E3 spec §5.5): EMV TLV + CRC16-CCITT-FALSE.
 * Pure domain — no framework dependencies. Golden vector asserted byte-exact.
 */
class BrCodeTest {

    @Test
    void golden_vector_matches_spec_exactly() {
        String pixKey = "dargent-dev-receber@example.com";
        String receiverName = "Dargent Dev LTDA";
        String receiverCity = "SAO PAULO";
        long amountCents = 10000;
        Txid txid = new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");

        String brcode = BrCode.of(pixKey, receiverName, receiverCity, amountCents, txid);

        assertThat(brcode).isEqualTo(
                "00020101021226530014BR.GOV.BCB.PIX0131dargent-dev-receber@example.com5204000053039865406100.005802BR5916Dargent Dev LTDA6009SAO PAULO622905258KD4Z9X2Q7W1M5T3R6Y0A1B2C6304EDD2");
        assertThat(brcode).hasSize(174);
    }
}
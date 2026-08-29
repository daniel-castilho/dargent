package io.dargent.payments.domain.br;

import io.dargent.payments.domain.model.Txid;
import java.nio.charset.StandardCharsets;

/**
 * BR Code composer (E3 spec §5.5): builds the EMV TLV payload string for dynamic PIX QR codes.
 * Pure domain — no framework dependencies. CRC16-CCITT-FALSE (poly 0x1021, init 0xFFFF).
 */
public final class BrCode {

    private BrCode() {}

    public static String of(String pixKey, String receiverName, String receiverCity,
            long amountCents, Txid txid) {
        StringBuilder tlv = new StringBuilder();

        // 00: Payload Format Indicator = "01"
        tlv.append(encodeTLV("00", "01"));

        // 01: Point of Initiation Method = "12" (dynamic QR)
        tlv.append(encodeTLV("01", "12"));

        // 26: Merchant Account Information (BR.GOV.BCB.PIX)
        // 00: BR.GOV.BCB.PIX (fixed)
        // 01: Pix Key
        StringBuilder mpi = new StringBuilder();
        mpi.append(encodeTLV("00", "BR.GOV.BCB.PIX"));
        mpi.append(encodeTLV("01", pixKey));
        tlv.append(encodeTLV("26", mpi.toString()));

        // 52: Merchant Category Code = "0000"
        tlv.append(encodeTLV("52", "0000"));

        // 53: Transaction Currency = "986" (BRL)
        tlv.append(encodeTLV("53", "986"));

        // 54: Transaction Amount = #.# decimal string (cents → "100.00")
        String amountStr = formatAmountCents(amountCents);
        tlv.append(encodeTLV("54", amountStr));

        // 58: Country Code = "BR"
        tlv.append(encodeTLV("58", "BR"));

        // 59: Merchant Name (≤ 25 chars)
        tlv.append(encodeTLV("59", truncate(receiverName, 25)));

        // 60: Merchant City (≤ 15 chars)
        tlv.append(encodeTLV("60", truncate(receiverCity, 15)));

        // 62: Additional Data Field Template
        // 05: Transaction Reference (txid, Bacen cap 25)
        StringBuilder adf = new StringBuilder();
        adf.append(encodeTLV("05", txid.value()));
        tlv.append(encodeTLV("62", adf.toString()));

        // Compute CRC16-CCITT-FALSE over all preceding bytes INCLUDING the "6304" tag/length
        // (per golden vector in spec §5.5)
        String payload = tlv.toString() + "6304";
        String crc = crc16CcittFalse(payload.getBytes(StandardCharsets.US_ASCII));
        tlv.append("6304").append(crc);

        return tlv.toString();
    }

    private static String encodeTLV(String tag, String value) {
        return tag + String.format("%02d", value.length()) + value;
    }

    private static String formatAmountCents(long amountCents) {
        long whole = amountCents / 100;
        long cents = amountCents % 100;
        return String.format("%d.%02d", whole, cents);
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /** CRC16-CCITT-FALSE (poly 0x1021, init 0xFFFF, no xor out, no reflect). */
    private static String crc16CcittFalse(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }

    /**
     * Encodes a cursor for keyset pagination (E3 spec §5.3).
     * Format: base64url("<txid>|<created_at_micros>")
     */
    public static String encodeCursor(String txid, long createdAtMicros) {
        String raw = txid + "|" + createdAtMicros;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }
}
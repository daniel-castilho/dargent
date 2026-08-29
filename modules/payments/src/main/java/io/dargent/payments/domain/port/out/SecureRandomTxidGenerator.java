package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;
import java.security.SecureRandom;

/**
 * Default {@link TxidGenerator}: cryptographically secure random draws over
 * {@code [A-Z0-9]} (design.md §4.2). Framework-free by design — lives beside
 * its port rather than in an adapter package.
 */
public class SecureRandomTxidGenerator implements TxidGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Override
    public Txid generate() {
        StringBuilder sb = new StringBuilder(25);
        for (int i = 0; i < 25; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return new Txid(sb.toString());
    }
}
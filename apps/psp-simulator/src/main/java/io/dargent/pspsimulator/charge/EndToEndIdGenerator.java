package io.dargent.pspsimulator.charge;

import java.security.SecureRandom;

/**
 * Network-wide PIX identifier, PSP-generated (spec §4.2 / appendix A): {@code 'E'} + 31 alphanumeric,
 * 32 total — matching the API-side {@code EndToEndId} VO regex {@code ^E[A-Za-z0-9]{31}$}.
 */
public final class EndToEndIdGenerator {

    private static final String ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TAIL_LENGTH = 31;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] id = new char[TAIL_LENGTH + 1];
        id[0] = 'E';
        for (int i = 1; i < id.length; i++) {
            id[i] = ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length()));
        }
        return new String(id);
    }
}
package io.dargent.payments.domain.model;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbox row identifier (spec §5.5): UUIDv7 per RFC 9562.
 *
 * <p>Layout (RFC 9562): 48-bit unix timestamp (ms, big-endian), version nibble = 7,
 * variant = 10 (RFC 4122), remaining 74 bits random.
 *
 * <p>Time-ordered identifier for outbox rows; generated from the injected {@link Clock}.
 * The {@code eventId} in the envelope stays v4 (deferred option, ideas ledger §6).
 */
public record OutboxId(UUID value) {

    /**
     * Generates a new UUIDv7 using the provided clock for the timestamp component.
     *
     * @param clock source of time for the 48-bit millisecond epoch (must not be null)
     * @return a new OutboxId with a fresh UUIDv7
     */
    public static OutboxId generate(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        return new OutboxId(uuidv7(clock.instant()));
    }

    private static UUID uuidv7(Instant instant) {
        long ms = instant.toEpochMilli();
        if (ms < 0) {
            throw new IllegalArgumentException("instant must not be before epoch");
        }
        if (ms > 0xFFFFFFFFFFFFL) {
            throw new IllegalArgumentException("instant exceeds 48-bit timestamp capacity");
        }

        // 48-bit timestamp (ms) in big-endian
        long ts = ms & 0xFFFFFFFFFFFFL;

        // 74 random bits (6 bytes + 2 bits)
        long rand = randomLong(74);

        // UUID layout (RFC 9562):
        // time_high (32 bits) | time_mid (16 bits) | time_low_and_version (16 bits)
        // | clock_seq_and_variant (16 bits) | node (48 bits)
        //
        // UUIDv7 places the 48-bit timestamp in time_high + time_mid + high 16 bits of time_low.
        // version (4 bits) = 7 at bits 48-51 (high nibble of time_low_and_version).
        // variant (2 bits) = 10 at bits 64-65 (high bits of clock_seq_and_variant).

        long timeHigh = (ts >>> 16) & 0xFFFFFFFFL;                    // ms >> 16
        int timeMid = (int) ((ts >>> 0) & 0xFFFF);                   // ms & 0xFFFF
        int timeLowAndVersion = (int) (((ts << 16) & 0x0FFF) | 0x7000); // version 7 in high nibble

        // clock_seq_and_variant: variant 10 (bits 6-7 = 10), rest random (14 bits)
        int clockSeqAndVariant = 0x8000 | (randomInt(14) & 0x3FFF); // variant 10 = 10xxxxxx

        // node (48 bits random)
        long node = randomLong(48);

        long mostSigBits = (timeHigh << 32) | ((long) timeMid << 16) | (timeLowAndVersion & 0xFFFF);
        long leastSigBits = ((long) clockSeqAndVariant << 48) | node;

        return new UUID(mostSigBits, leastSigBits);
    }

    private static long randomLong(int bits) {
        if (bits <= 0) {
            throw new IllegalArgumentException("bits must be >= 1");
        }
        if (bits <= 64) {
            long r = java.util.concurrent.ThreadLocalRandom.current().nextLong();
            if (bits == 64) return r;
            return r & ((1L << bits) - 1);
        }
        // For bits > 64, combine multiple 64-bit random values
        long result = 0;
        int remaining = bits;
        while (remaining > 0) {
            int chunk = Math.min(remaining, 64);
            long chunkVal = java.util.concurrent.ThreadLocalRandom.current().nextLong();
            if (chunk < 64) {
                chunkVal &= (1L << chunk) - 1;
            }
            result = (result << chunk) | chunkVal;
            remaining -= chunk;
        }
        return result;
    }

    private static int randomInt(int bits) {
        if (bits <= 0 || bits > 32) {
            throw new IllegalArgumentException("bits must be 1..32");
        }
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << bits);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
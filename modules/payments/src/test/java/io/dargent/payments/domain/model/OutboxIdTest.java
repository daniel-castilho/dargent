package io.dargent.payments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for {@link OutboxId} (UUIDv7 per RFC 9562).
 */
class OutboxIdTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void version_nibble_is_7() {
        OutboxId id = OutboxId.generate(FIXED_CLOCK);
        int version = (id.value().version());
        assertThat(version).isEqualTo(7);
    }

    @Test
    void variant_bits_are_10() {
        OutboxId id = OutboxId.generate(FIXED_CLOCK);
        int variant = id.value().variant();
        assertThat(variant).isEqualTo(2); // RFC 4122 variant = 2 (10 in binary)
    }

    @Test
    void monotonic_non_decreasing_under_fixed_clock() {
        Instant fixed = Instant.parse("2026-08-30T12:00:00Z");
        Clock clock = Clock.fixed(fixed, java.time.ZoneOffset.UTC);

        OutboxId first = OutboxId.generate(clock);
        OutboxId second = OutboxId.generate(clock);

        // Under a fixed clock, the timestamp component is identical
        // The random bits may vary, so we only assert the timestamp component is equal
        long ts1 = timestampComponent(first.value());
        long ts2 = timestampComponent(second.value());
        assertThat(ts1).isEqualTo(ts2);
    }

    @Test
    void uniqueness_across_10k_generations() {
        Clock clock = Clock.systemUTC();
        Set<UUID> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            OutboxId id = OutboxId.generate(Clock.systemUTC());
            assertThat(seen).doesNotContain(id.value());
            seen.add(id.value());
        }
    }

    @Test
    void timestamp_component_is_reasonable() {
        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

        OutboxId id = OutboxId.generate(clock);
        long ts = timestampComponent(id.value());

        long expectedMs = now.toEpochMilli();
        assertThat(ts).isEqualTo(expectedMs);
    }

    @Test
    void version_nibble_is_7_for_multiple_generations() {
        for (int i = 0; i < 100; i++) {
            OutboxId id = OutboxId.generate(Clock.systemUTC());
            assertThat(id.value().version()).isEqualTo(7);
        }
    }

    @Test
    void variant_is_rfc4122() {
        for (int i = 0; i < 100; i++) {
            OutboxId id = OutboxId.generate(Clock.systemUTC());
            assertThat(id.value().variant()).isEqualTo(2);
        }
    }

    // Extract the 48-bit timestamp component from UUID (bits 127..80)
    private static long timestampComponent(UUID uuid) {
        long mostSig = uuid.getMostSignificantBits();
        // UUIDv7: 48-bit timestamp in bits 127..80 (most significant 48 bits)
        return mostSig >>> 16;
    }
}
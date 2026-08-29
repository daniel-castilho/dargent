package io.dargent.pspsimulator.charge;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    private static final Pattern END_TO_END = Pattern.compile("^E[A-Za-z0-9]{31}$");
    private static final Pattern EVENT_ID = Pattern.compile("^psp-evt-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Test
    void end_to_end_ids_match_E_plus_31_alphanumeric_over_100_samples() {
        EndToEndIdGenerator generator = new EndToEndIdGenerator();
        for (int i = 0; i < 100; i++) {
            assertThat(generator.generate()).matches(END_TO_END);
        }
    }

    @Test
    void end_to_end_ids_are_unique_over_1000_samples() {
        EndToEndIdGenerator generator = new EndToEndIdGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }

    @Test
    void event_ids_match_psp_evt_uuid4_format() {
        EventIdGenerator generator = new EventIdGenerator();
        assertThat(generator.generate()).matches(EVENT_ID);
    }

    @Test
    void event_ids_are_unique_over_1000_samples() {
        EventIdGenerator generator = new EventIdGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }
}
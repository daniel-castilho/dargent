package io.dargent.pspsimulator.config;

import java.time.Clock;
import java.util.Random;

import io.dargent.pspsimulator.charge.ChargeStore;
import io.dargent.pspsimulator.charge.EndToEndIdGenerator;
import io.dargent.pspsimulator.charge.EventIdGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Simulator ambient beans: the injected {@link Clock} (the only way to read "now" anywhere in the
 * app — spec S1 acceptance: zero {@code Instant.now()} outside this bean), the seedable
 * {@link Random} backing the probabilistic chaos knobs, and the in-memory charge beans. Domain
 * classes stay framework-free — they are registered here, never annotated.
 */
@Configuration
@EnableConfigurationProperties({PspProfile.class, WebhookSecret.class, ChaosProperties.class})
public class PspSimulatorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Random chaosRandom(ChaosProperties properties) {
        Long seed = properties.getSeed();
        return seed == null ? new Random() : new Random(seed);
    }

    @Bean
    ChargeStore chargeStore() {
        return new ChargeStore();
    }

    @Bean
    EndToEndIdGenerator endToEndIdGenerator() {
        return new EndToEndIdGenerator();
    }

    @Bean
    EventIdGenerator eventIdGenerator() {
        return new EventIdGenerator();
    }
}
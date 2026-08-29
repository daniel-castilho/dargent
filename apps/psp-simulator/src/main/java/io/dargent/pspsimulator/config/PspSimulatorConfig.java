package io.dargent.pspsimulator.config;

import java.time.Clock;
import java.util.Random;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Simulator ambient beans: the injected {@link Clock} (the only way to read "now" anywhere in the
 * app — spec S1 acceptance: zero {@code Instant.now()} outside this bean) and the seedable
 * {@link Random} backing the probabilistic chaos knobs.
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
}
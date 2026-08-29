package io.dargent.pspsimulator.config;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PspConfigBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class)
            .withInitializer(ctx -> {
                try {
                    List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                            .load("simulator-config", new ClassPathResource("application.yaml"));
                    ctx.getEnvironment().getPropertySources().addLast(yaml.get(0));
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot load application.yaml", e);
                }
            });

    @Configuration
    @Import(PspSimulatorConfig.class)
    static class BindingConfig {
    }

    @Test
    void binds_m0_compatible_defaults_from_application_yaml() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PspProfile.class).hasSingleBean(WebhookSecret.class)
                    .hasSingleBean(ChaosProperties.class);

            ChaosProperties chaos = ctx.getBean(ChaosProperties.class);
            assertThat(chaos.isWebhookDuplicate()).isFalse();
            assertThat(chaos.getWebhookDelayMs()).isZero();
            assertThat(chaos.getWebhookDropRate()).isZero();
            assertThat(chaos.getPspErrorRate()).isZero();
            assertThat(chaos.getPspLatencyMs()).isZero();
            assertThat(chaos.getSeed()).isNull();

            PspProfile profile = ctx.getBean(PspProfile.class);
            assertThat(profile.pixKey()).isEqualTo("dargent-dev-receber@example.com");
            assertThat(profile.receiverName()).isEqualTo("Dargent Dev LTDA");
            assertThat(profile.receiverCity()).isEqualTo("SAO PAULO");

            assertThat(ctx.getBean(WebhookSecret.class).webhookSecret()).isEqualTo("dev-only-secret");
        });
    }

    @Test
    void binds_chaos_overrides_and_clamps_bounds() {
        runner.withPropertyValues(
                "dargent.psp.chaos.webhook-duplicate=true",
                "dargent.psp.chaos.webhook-delay-ms=-7",
                "dargent.psp.chaos.webhook-drop-rate=0.5",
                "dargent.psp.chaos.psp-error-rate=0.25",
                "dargent.psp.chaos.psp-latency-ms=99999",
                "dargent.psp.chaos.seed=42")
                .run(ctx -> {
                    ChaosProperties chaos = ctx.getBean(ChaosProperties.class);
                    assertThat(chaos.isWebhookDuplicate()).isTrue();
                    assertThat(chaos.getWebhookDelayMs()).isZero();
                    assertThat(chaos.getWebhookDropRate()).isEqualTo(0.5);
                    assertThat(chaos.getPspErrorRate()).isEqualTo(0.25);
                    assertThat(chaos.getPspLatencyMs()).isEqualTo(30_000);
                    assertThat(chaos.getSeed()).isEqualTo(42L);
                });
    }

    @Test
    void seeded_chaos_random_is_deterministic() {
        runner.withPropertyValues("dargent.psp.chaos.seed=7").run(ctx -> {
            Random random = ctx.getBean(Random.class);
            assertThat(random.nextDouble()).isEqualTo(new Random(7).nextDouble());
        });
    }
}
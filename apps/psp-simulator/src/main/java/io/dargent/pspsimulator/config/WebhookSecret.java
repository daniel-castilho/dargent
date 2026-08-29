package io.dargent.pspsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The webhook signing secret shared with the platform (E2 spec §3.2).
 * Default {@code dev-only-secret} is a documented test value (AGENTS.md §4.2 — secrets come from
 * the environment; Compose dev defaults stay in place until the M1 {@code ConfigValidator}).
 */
@ConfigurationProperties("dargent.psp")
public record WebhookSecret(String webhookSecret) {
}
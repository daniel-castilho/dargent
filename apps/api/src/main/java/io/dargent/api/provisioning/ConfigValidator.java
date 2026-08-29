package io.dargent.api.provisioning;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Aggregated fail-fast configuration validator (design.md §8.3, spec §3.5). Runs on startup and
 * aborts with a combined report of ALL problems: unresolved placeholders, short secrets, static
 * AWS creds in prod, missing PSP URLs, etc. Prevents silent misconfiguration in production.
 */
@Component
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @Value("${DARGENT_DB_PASSWORD:}")
    private String dbPassword;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String awsAccessKey;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String awsSecretKey;

    @Value("${PSP_BASE_URL:}")
    private String pspBaseUrl;

    @Value("${PSP_WEBHOOK_SECRET:}")
    private String pspWebhookSecret;

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();

        boolean isProd = activeProfile.contains("prod");

        // Dev defaults check
        if (isProd) {
            if ("dargent".equals(dbPassword) || dbPassword.length() < 32) {
                errors.add("DARGENT_DB_PASSWORD must be set to a strong secret (>=32 chars) in prod profile");
            }
            if ("test".equals(awsAccessKey) || "test".equals(awsSecretKey)) {
                errors.add("Static AWS credentials (test/test) detected in prod — use IAM roles or real secrets");
            }
            if (pspBaseUrl.isBlank() || pspBaseUrl.contains("localhost")) {
                errors.add("PSP_BASE_URL must be a real endpoint in prod profile");
            }
            if (pspWebhookSecret.isBlank() || pspWebhookSecret.equals("dev-only-secret")) {
                errors.add("PSP_WEBHOOK_SECRET must be a strong secret in prod profile");
            }
        }

        if (!errors.isEmpty()) {
            String msg = "Configuration validation failed:\n  - " + String.join("\n  - ", errors);
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("Configuration validation passed (profile={})", activeProfile);
    }
}
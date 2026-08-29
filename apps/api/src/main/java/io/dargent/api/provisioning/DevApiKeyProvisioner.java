package io.dargent.api.provisioning;

import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.security.ApiKeyRepository;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Development-only API key provisioner (E3 spec §5.9): on {@code dev} profile, if
 * {@code DARGENT_DEV_API_KEY} is set, upserts the key into {@code payments.api_keys} bound to a
 * deterministic dev merchant. Idempotent — safe to run on every boot.
 */
@Component
@Profile("dev")
public class DevApiKeyProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DevApiKeyProvisioner.class);
    private static final UUID DEV_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEV_KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final JdbcClient jdbc;
    private final String devKey;

    public DevApiKeyProvisioner(JdbcClient jdbc, @Value("${DARGENT_DEV_API_KEY:}") String devKey) {
        this.jdbc = jdbc;
        this.devKey = devKey;
    }

    @PostConstruct
    public void provision() {
        if (devKey == null || devKey.isBlank()) {
            log.info("DARGENT_DEV_API_KEY not set — skipping dev key provisioning");
            return;
        }
        String prefix = ApiKeyHasher.prefix(devKey);
        String hash = ApiKeyHasher.hash(devKey);

int updated = jdbc.sql(
                """
                insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at)
                values (:id, :merchant, 'dev-key', :prefix, :hash, now(), null)
                on conflict (key_hash) do update set revoked_at = null
                """)
                .param("id", DEV_KEY_ID)
                .param("merchant", DEV_MERCHANT_ID)
                .param("prefix", prefix)
                .param("hash", hash)
                .update();

        log.info("Dev API key provisioned (updated={}) for merchant {}", updated, DEV_MERCHANT_ID);
    }
}
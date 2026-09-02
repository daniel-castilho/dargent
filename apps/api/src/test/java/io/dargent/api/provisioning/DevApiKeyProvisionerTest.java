package io.dargent.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.security.JdbcApiKeyRepository;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dev key provisioning idempotency (E3 spec §5.9): running the provisioner multiple times with the
 * same key produces exactly one row for the deterministic dev merchant. Uses Testcontainers
 * PostgreSQL for the real DB. A focused boot context (DataSource + Flyway + TestProvisioner)
 * instead of the full app, so the dev-profile {@code DevApiKeyProvisioner}'s @PostConstruct
 * never races Flyway at startup (BD-18).
 */
@SpringBootTest(
    classes = DevApiKeyProvisionerTest.TestConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class DevApiKeyProvisionerTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("DARGENT_DB_HOST", postgres::getHost);
        registry.add("DARGENT_DB_PORT", postgres::getFirstMappedPort);
        registry.add("DARGENT_DB_NAME", postgres::getDatabaseName);
        registry.add("DARGENT_DB_USER", postgres::getUsername);
        registry.add("DARGENT_DB_PASSWORD", postgres::getPassword);
        registry.add("DARGENT_DEV_API_KEY", () -> "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst");
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestProvisioner provisioner;

    @Test
    void provisioner_is_idempotent() {
        provisioner.provision();

        // Verify exactly one row exists
        var count = jdbc.sql("select count(*) from payments.api_keys where key_hash = :hash")
                .param("hash", ApiKeyHasher.hash("psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst"))
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void provisioned_key_has_correct_merchant_and_prefix() {
        provisioner.provision();
        var repo = new JdbcApiKeyRepository(jdbc);
        String rawKey = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        var keyOpt = repo.findByPrefix(ApiKeyHasher.prefix(rawKey));
        assertThat(keyOpt).isPresent();
        var record = keyOpt.get();
        assertThat(record.merchantId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(record.keyPrefix()).isEqualTo(ApiKeyHasher.prefix(rawKey));
        assertThat(record.keyHash()).isEqualTo(ApiKeyHasher.hash(rawKey));
        assertThat(record.revokedAt()).isNull();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("payments", "ledger", "notifications")
                    .locations(
                            "classpath:db/migration/payments",
                            "classpath:db/migration/ledger",
                            "classpath:db/migration/notifications"
                    )
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        TestProvisioner testProvisioner(JdbcClient jdbc) {
            return new TestProvisioner(jdbc);
        }
    }

    /** Test-specific provisioner without @PostConstruct, runs after Flyway. */
    static class TestProvisioner {
        private final JdbcClient jdbc;
        private final String devKey;

        TestProvisioner(JdbcClient jdbc) {
            this.jdbc = jdbc;
            this.devKey = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        }

        void provision() {
            String prefix = ApiKeyHasher.prefix(devKey);
            String hash = ApiKeyHasher.hash(devKey);
            UUID DEV_KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
            UUID DEV_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

            jdbc.sql(
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
        }
    }
}
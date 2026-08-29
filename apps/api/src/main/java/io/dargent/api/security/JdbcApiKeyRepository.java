package io.dargent.api.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the API key repository. */
@Repository
public class JdbcApiKeyRepository implements ApiKeyRepository {

    private final JdbcClient jdbc;

    public JdbcApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiKeyRecord> findByPrefix(String prefix) {
        return jdbc.sql(
                """
                select id, merchant_id, key_prefix, key_hash, revoked_at
                from payments.api_keys
                where key_prefix = :prefix and revoked_at is null
                """)
                .param("prefix", prefix)
                .query(ApiKeyRecord.class)
                .optional();
    }
}
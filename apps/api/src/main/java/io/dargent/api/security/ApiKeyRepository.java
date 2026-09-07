package io.dargent.api.security;

import java.util.Optional;

/** Repository for API key lookups (E3 spec §3.2). */
public interface ApiKeyRepository {

    Optional<ApiKeyRecord> findByPrefix(String prefix);
}

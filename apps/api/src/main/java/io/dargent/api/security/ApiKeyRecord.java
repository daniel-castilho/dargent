package io.dargent.api.security;

import java.time.Instant;
import java.util.UUID;

/** Read-only record matching the api_keys table. */
public record ApiKeyRecord(UUID id, UUID merchantId, String keyPrefix, String keyHash, Instant revokedAt) {}
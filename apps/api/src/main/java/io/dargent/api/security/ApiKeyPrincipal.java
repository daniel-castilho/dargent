package io.dargent.api.security;

import java.util.UUID;

/** Principal carrying the authenticated API key's merchant and key identity. */
public record ApiKeyPrincipal(UUID merchantId, UUID keyId) implements org.springframework.security.core.Authentication {

    @Override public String getName() { return merchantId.toString(); }
    @Override public boolean isAuthenticated() { return true; }
    @Override public java.util.Collection<org.springframework.security.core.GrantedAuthority> getAuthorities() {
        return java.util.Collections.emptyList();
    }
    @Override public Object getCredentials() { return null; }
    @Override public Object getDetails() { return null; }
    @Override public Object getPrincipal() { return this; }
    @Override public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {}
}
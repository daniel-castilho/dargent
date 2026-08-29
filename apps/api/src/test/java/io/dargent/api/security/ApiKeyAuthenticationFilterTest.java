package io.dargent.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dargent.api.error.ErrorCode;
import io.dargent.api.error.ErrorResponseWriter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * API key filter contract (E3 spec §5.9): missing/invalid key → 401 via single writer; valid key
 * → sets principal in SecurityContext. Pure unit with mocks — no Spring context.
 */
class ApiKeyAuthenticationFilterTest {

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ErrorResponseWriter writer = mock(ErrorResponseWriter.class);
    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(repository, writer);

    @Test
    void missing_authorization_header_returns_401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(writer).write(request, response, ErrorCode.UNAUTHORIZED, "Missing or invalid Authorization header", (Map<String, String>) null);
    }

    @Test
    void invalid_bearer_format_returns_401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(writer).write(request, response, ErrorCode.UNAUTHORIZED, "Missing or invalid Authorization header", (Map<String, String>) null);
    }

    @Test
    void empty_bearer_token_returns_401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(writer).write(request, response, ErrorCode.UNAUTHORIZED, "Empty API key", (Map<String, String>) null);
    }

    @Test
    void unknown_prefix_returns_401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer psp_test_unknownprefix123456789012345678901234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(repository.findByPrefix("psp_test_")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(writer).write(request, response, ErrorCode.UNAUTHORIZED, "Invalid API key", (Map<String, String>) null);
    }

    @Test
    void valid_key_sets_principal_in_security_context() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        String rawKey = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        String prefix = ApiKeyHasher.prefix(rawKey);
        String hash = ApiKeyHasher.hash(rawKey);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer " + rawKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        final org.springframework.security.authentication.UsernamePasswordAuthenticationToken[] capturedAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken[1];

        Servlet testServlet = new Servlet() {
            @Override public void init(ServletConfig config) {}
            @Override public ServletConfig getServletConfig() { return null; }
            @Override public void service(ServletRequest req, ServletResponse res) {
                capturedAuth[0] = (org.springframework.security.authentication.UsernamePasswordAuthenticationToken)
                        SecurityContextHolder.getContext().getAuthentication();
            }
            @Override public String getServletInfo() { return "test"; }
            @Override public void destroy() {}
        };

        Filter observing = (req, res, chain) -> capturedAuth[0] = (org.springframework.security.authentication.UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        MockFilterChain chain = new MockFilterChain(testServlet, observing);

        when(repository.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyRecord(keyId, merchantId, prefix, hash, null)));

        filter.doFilter(request, response, chain);

        assertThat(capturedAuth[0]).isNotNull();
        assertThat(capturedAuth[0].getPrincipal()).isInstanceOf(ApiKeyPrincipal.class);
        assertThat(((ApiKeyPrincipal) capturedAuth[0].getPrincipal()).merchantId()).isEqualTo(merchantId);
        assertThat(((ApiKeyPrincipal) capturedAuth[0].getPrincipal()).keyId()).isEqualTo(keyId);
    }

    @Test
    void revoked_key_returns_401() throws Exception {
        String rawKey = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        String prefix = ApiKeyHasher.prefix(rawKey);
        String hash = ApiKeyHasher.hash(rawKey);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer " + rawKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(repository.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyRecord(UUID.randomUUID(), UUID.randomUUID(), prefix, hash, java.time.Instant.now())));

        filter.doFilter(request, response, chain);

        verify(writer).write(request, response, ErrorCode.UNAUTHORIZED, "API key revoked", (Map<String, String>) null);
    }

    @Test
    void wrong_hash_returns_401() throws Exception {
        String rawKey = "psp_test_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst";
        String prefix = ApiKeyHasher.prefix(rawKey);
        String wrongHash = "b".repeat(64);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer " + rawKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(repository.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyRecord(UUID.randomUUID(), UUID.randomUUID(), prefix, wrongHash, null)));

        filter.doFilter(request, response, chain);

        verify(writer).write(request, response, ErrorCode.UNAUTHORIZED, "Invalid API key", (Map<String, String>) null);
    }
}
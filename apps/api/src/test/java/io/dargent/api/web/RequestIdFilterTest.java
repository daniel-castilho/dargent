package io.dargent.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

/**
 * {@code X-Request-Id} correlation contract (E3 spec §5.4): a valid accepted id is echoed as-is; an
 * invalid id is replaced with a generated one (never rejected); an absent id is generated; the final
 * id is exposed on the response and in MDC. Pure unit — no servlet container.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void echoes_a_valid_incoming_request_id() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader(RequestIdFilter.HEADER, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-12345678");
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("req-12345678");
    }

    @Test
    void replaces_an_invalid_incoming_request_id_with_a_uuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader(RequestIdFilter.HEADER, "bad id!");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertThat(echoed).matches("[A-Za-z0-9-]{8,64}");
        assertThat(echoed).isNotEqualTo("bad id!");
    }

    @Test
    void generates_a_uuid_when_absent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[A-Za-z0-9-]{8,64}");
    }

    @Test
    void exposes_request_id_in_mdc_during_processing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader(RequestIdFilter.HEADER, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] seenInChain = new String[1];

        Servlet testServlet = new Servlet() {
            @Override public void init(ServletConfig config) {}
            @Override public ServletConfig getServletConfig() { return null; }
            @Override public void service(ServletRequest req, ServletResponse res) {}
            @Override public String getServletInfo() { return "test"; }
            @Override public void destroy() {}
        };

        Filter observing = (req, res, chain) -> seenInChain[0] = MDC.get("requestId");
        MockFilterChain chain = new MockFilterChain(testServlet, observing);

        filter.doFilter(request, response, chain);

        assertThat(seenInChain[0]).isEqualTo("req-12345678");
        assertThat(MDC.get("requestId")).isNull();
    }
}
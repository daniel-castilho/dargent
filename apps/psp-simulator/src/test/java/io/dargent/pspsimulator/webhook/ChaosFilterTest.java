package io.dargent.pspsimulator.webhook;

import java.util.Random;

import io.dargent.pspsimulator.config.ChaosProperties;
import io.dargent.pspsimulator.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request-side chaos knobs (spec §6), exercised directly on the filter — forced extreme modes, zero
 * probabilistic assertions. Health (non-{@code /cobs}) traffic must never be squashed.
 */
class ChaosFilterTest {

    private final ChaosProperties chaos = new ChaosProperties();
    private final ChaosFilter filter = new ChaosFilter(chaos, new Random(42), new ObjectMapper());

    @Test
    void error_rate_1_squashes_cobs_calls_with_503_psp_unavailable_without_touching_the_chain() throws Exception {
        chaos.setPspErrorRate(1.0);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cobs/ABC123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        ErrorResponse error = new ObjectMapper().readValue(response.getContentAsByteArray(), ErrorResponse.class);
        assertThat(error.code()).isEqualTo("psp_unavailable");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void error_rate_1_never_touches_non_cobs_traffic() throws Exception {
        chaos.setPspErrorRate(1.0);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void error_rate_0_passes_every_cobs_call_through() throws Exception {
        chaos.setPspErrorRate(0.0);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cobs/ABC123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void latency_ms_is_applied_before_handling_but_does_not_halt_the_request() throws Exception {
        chaos.setPspLatencyMs(150);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cobs/ABC123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        long start = System.nanoTime();
        filter.doFilter(request, response, chain);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(chain.getRequest()).isNotNull();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150L);
    }
}
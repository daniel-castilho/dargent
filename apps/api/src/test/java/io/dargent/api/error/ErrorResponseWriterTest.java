package io.dargent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single writer's contract (E3 spec §5.4): RFC 9457 body shape for a validation error and for a
 * {@code 500 internal} that must never leak the exception message. Pure unit — no bean wiring, no DB.
 */
class ErrorResponseWriterTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ErrorResponseWriter writer = new ErrorResponseWriter(mapper);

    @Test
    void validation_error_emits_rfc9457_body_with_field_map() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, ErrorCode.INVALID_REQUEST, "Validation failed",
                Map.of("amount", "must be greater than 0"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        ErrorResponse body = mapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.type()).isEqualTo("about:blank");
        assertThat(body.title()).isEqualTo("Invalid request");
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.code()).isEqualTo("invalid_request");
        assertThat(body.detail()).isEqualTo("Validation failed");
        assertThat(body.fields()).containsEntry("amount", "must be greater than 0");
    }

    @Test
    void internal_error_never_leaks_exception_message() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Throwable secret = new IllegalStateException("db password=super-secret leaked");
        writer.write(request, response, ErrorCode.INTERNAL, null, null, secret);

        assertThat(response.getStatus()).isEqualTo(500);
        ErrorResponse body = mapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.code()).isEqualTo("internal");
        assertThat(body.detail()).doesNotContain("password");
        assertThat(body.detail()).doesNotContain("super-secret");
        assertThat(body.detail()).isNotBlank();
    }

    @Test
    void psp_unavailable_is_502_bad_gateway() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, ErrorCode.PSP_UNAVAILABLE, "payment provider exhausted");

        assertThat(response.getStatus()).isEqualTo(502);
        ErrorResponse body = mapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.code()).isEqualTo("psp_unavailable");
    }
}

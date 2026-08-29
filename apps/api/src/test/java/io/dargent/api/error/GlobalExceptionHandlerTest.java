package io.dargent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.payments.domain.exception.InvalidTransitionException;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exception → catalog mapping through the single writer (E3 spec §5.4): domain transition violations
 * are {@code 409 invalid_transition}, unknown/malformed routes are a canonical {@code 404 not_found}
 * (never a 500), and unreadable bodies are {@code 400 invalid_request}. Pure unit — no bean wiring.
 */
class GlobalExceptionHandlerTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new ErrorResponseWriter(mapper));

    @Test
    void invalid_transition_maps_to_409_invalid_transition() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new InvalidTransitionException(new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C"),
                PaymentStatus.PENDING, PaymentStatus.REFUNDED);

        handler.invalidTransition(request, response, ex);

        assertThat(response.getStatus()).isEqualTo(409);
        ErrorResponse body = mapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.code()).isEqualTo("invalid_transition");
    }

    @Test
    void unknown_route_maps_to_canonical_404_not_500() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/nope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/v1/nope", null);

        handler.notFound(request, response, ex);

        assertThat(response.getStatus()).isEqualTo(404);
        ErrorResponse body = mapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.code()).isEqualTo("not_found");
        assertThat(body.status()).isEqualTo(404);
    }

    @Test
    void unreadable_body_maps_to_400_invalid_request() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new org.springframework.http.converter.HttpMessageNotReadableException(
                "boom", new MockHttpInputMessage("boom".getBytes()));

        handler.unreadable(request, response, ex);

        assertThat(response.getStatus()).isEqualTo(400);
        ErrorResponse body = mapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.code()).isEqualTo("invalid_request");
        assertThat(body.fields()).containsKey("body");
    }
}

package io.dargent.pspsimulator.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.dargent.pspsimulator.webhook.RecordingWebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice tests for the instant card profile (M5 S1, D2 adjudication): approval is decided at create
 * (born PAID, exactly ONE webhook dispatch, same engine as the payer-bank path); the magic-amount
 * decline answers {@code 402 card_declined} and is never stored. The real dispatcher is substituted
 * by a recorded fake so delivery is asserted, not awaited.
 */
@SpringBootTest
@Import(CardChargesControllerTest.CardTestConfig.class)
class CardChargesControllerTest {

    private static final String CALLBACK = "http://api-blue:8080/webhooks/psp";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RecordingWebhookDispatcher dispatcher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        dispatcher.clear();
    }

    private static String body(String txid, long amount, String token) {
        return "{\"txid\":\"%s\",\"amount\":%d,\"callbackUrl\":\"%s\",\"token\":\"%s\"}"
                .formatted(txid, amount, CALLBACK, token);
    }

    @Test
    void approved_charge_is_born_paid_and_dispatches_exactly_one_webhook() throws Exception {
        String txid = "AKD4Z9X2Q7W1M5T3R6Y0A1B2C";
        mockMvc.perform(post("/card-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(txid, 10000, "tok_test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.endToEndId").isNotEmpty())
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        assertThat(dispatcher.deliveryCount()).isEqualTo(1);
        assertThat(dispatcher.delivered().get(0).txid()).isEqualTo(txid);
        assertThat(dispatcher.delivered().get(0).endToEndId()).isNotBlank();
        assertThat(dispatcher.delivered().get(0).eventId()).isNotBlank();
    }

    @Test
    void magic_amount_decline_answers_402_and_dispatch_nothing() throws Exception {
        String txid = "BKE5E0Y4R8X2N6T4S7Z1B2C0D";
        mockMvc.perform(post("/card-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(txid, 4444, "tok_test")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("card_declined"));

        assertThat(dispatcher.deliveryCount()).isZero(); // declined charges never fire a webhook
    }

    @Test
    void get_returns_the_approved_charge_as_paid_truth() throws Exception {
        String txid = "CLF6P5S9W3Y7C6T5U8B3C4D1E";
        mockMvc.perform(post("/card-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(txid, 2500, "tok_test")))
                .andExpect(status().isCreated());
        dispatcher.clear();

        mockMvc.perform(get("/card-charges/{txid}", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amount").value(2500))
                .andExpect(jsonPath("$.endToEndId").isNotEmpty())
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
    }

    @Test
    void create_without_token_returns_400_invalid_token() throws Exception {
        String txid = "DM0Q7T6Z3X9E8V7W9B4C5D2E3";
        mockMvc.perform(post("/card-charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txid\":\"%s\",\"amount\":1000,\"callbackUrl\":\"%s\"}".formatted(txid, CALLBACK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @TestConfiguration
    static class CardTestConfig {
        @Bean
        @Primary
        RecordingWebhookDispatcher recordingDispatcher() {
            return new RecordingWebhookDispatcher();
        }
    }
}

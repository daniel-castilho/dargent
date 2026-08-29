package io.dargent.pspsimulator.charge;

import java.time.Instant;
import java.util.regex.Pattern;

import io.dargent.pspsimulator.webhook.RecordingWebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for the payer bank ({@link ChargePaymentsController}, E2 spec §5.3). The
 * {@link io.dargent.pspsimulator.webhook.WebhookDispatcher} is stubbed with a recording fake declared
 * @Primary over the S4 placeholder — the exact seat the S5 async engine will take.
 */
@SpringBootTest
class ChargePaymentsControllerTest {

    private static final Pattern END_TO_END = Pattern.compile("^E[A-Za-z0-9]{31}$");
    private static final String CALLBACK = "http://api-blue:8080/webhooks/psp";
    private static final String FUTURE = "2030-01-01T00:00:00Z";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ChargeStore store;

    @Autowired
    private RecordingWebhookDispatcher recordingDispatcher;

    private MockMvc mockMvc;

    @TestConfiguration
    static class FakeDispatcherConfig {
        @Bean
        RecordingWebhookDispatcher recordingWebhookDispatcher() {
            return new RecordingWebhookDispatcher();
        }

        @Bean
        @Primary
        io.dargent.pspsimulator.webhook.WebhookDispatcher primaryRecordingDispatcher(
                RecordingWebhookDispatcher recording) {
            return recording;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        recordingDispatcher.clear();
    }

    private String createCharge(String txid) throws Exception {
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txid\":\"%s\",\"amount\":10000,\"expiresAt\":\"%s\",\"callbackUrl\":\"%s\",\"description\":\"Order #123\"}"
                                .formatted(txid, FUTURE, CALLBACK)))
                .andExpect(status().isCreated());
        return txid;
    }

    @Test
    void paying_an_open_charge_returns_200_with_paid_details_and_dispatch_once() throws Exception {
        String txid = createCharge("AKD4Z9X2Q7W1M5T3R6Y0A1B2C");

        mockMvc.perform(post("/cobs/{txid}/payments", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.endToEndId").value(matchesPattern(END_TO_END)))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        assertThat(recordingDispatcher.deliveryCount()).isEqualTo(1);
        assertThat(recordingDispatcher.delivered().get(0).txid()).isEqualTo(txid);
        assertThat(mockMvc.perform(get("/cobs/{txid}", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andReturn().getResponse().getContentAsString()).contains(recordedEndToEndId(txid));
    }

    private String recordedEndToEndId(String txid) {
        return recordingDispatcher.delivered().stream()
                .filter(c -> c.txid().equals(txid))
                .findFirst()
                .orElseThrow()
                .endToEndId();
    }

    @Test
    void paying_an_already_paid_charge_is_rejected_with_409_already_paid() throws Exception {
        String txid = createCharge("BKD4Z9X2Q7W1M5T3R6Y0A1B2C");
        mockMvc.perform(post("/cobs/{txid}/payments", txid)).andExpect(status().isOk());

        mockMvc.perform(post("/cobs/{txid}/payments", txid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_paid"));

        assertThat(recordingDispatcher.deliveryCount()).isEqualTo(1);
    }

    @Test
    void paying_an_expired_charge_is_rejected_with_409_charge_expired() throws Exception {
        String txid = "CKD4Z9X2Q7W1M5T3R6Y0A1B2C";
        store.putIfAbsent(new Charge(txid, 10_000,
                Instant.parse("2020-01-01T00:00:00Z"), CALLBACK, "stale order"));

        mockMvc.perform(post("/cobs/{txid}/payments", txid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("charge_expired"));

        assertThat(recordingDispatcher.deliveryCount()).isZero();
    }

    @Test
    void paying_an_unknown_charge_is_rejected_with_404_cob_not_found() throws Exception {
        mockMvc.perform(post("/cobs/{txid}/payments", "9XXXXXXXXX9XXXXXXXXX9XXXX"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("cob_not_found"));

        assertThat(recordingDispatcher.deliveryCount()).isZero();
    }

    @Test
    void a_paid_charge_is_visible_as_paid_via_get() throws Exception {
        String txid = createCharge("DKD4Z9X2Q7W1M5T3R6Y0A1B2C");

        mockMvc.perform(post("/cobs/{txid}/payments", txid))
                .andExpect(status().isOk());

        mockMvc.perform(get("/cobs/{txid}", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.endToEndId").value(matchesPattern(END_TO_END)))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
    }
}
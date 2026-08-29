package io.dargent.pspsimulator.charge;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link ChargesController} against the real MockMvc dispatcher. Boot 4.1.1 ships no
 * {@code @WebMvcTest} web-slice autoconfiguration in spring-boot-test-autoconfigure (documented
 * deviation), so the full app context is booted — tiny and fast here, no containers. Each test uses a
 * distinct txid so the shared ChargeStore never collides.
 */
@SpringBootTest
class ChargesControllerTest {

    private static final String CALLBACK = "http://api-blue:8080/webhooks/psp";
    private static final String FUTURE = "2030-01-01T00:00:00Z";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ChargeStore store;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static String body(String txid, String amount, String expiresAt, String callbackUrl) {
        return "{\"txid\":\"%s\",\"amount\":%s,\"expiresAt\":\"%s\",\"callbackUrl\":\"%s\",\"description\":\"Order #123\"}"
                .formatted(txid, amount, expiresAt, callbackUrl);
    }

    @Test
    void creating_a_charge_returns_201_with_charge_and_profile_fields() throws Exception {
        String txid = "AKD4Z9X2Q7W1M5T3R6Y0A1B2C";
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(txid, "10000", FUTURE, CALLBACK)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.expiresAt").value(FUTURE))
                .andExpect(jsonPath("$.callbackUrl").value(CALLBACK))
                .andExpect(jsonPath("$.description").value("Order #123"))
                .andExpect(jsonPath("$.pixKey").value("dargent-dev-receber@example.com"))
                .andExpect(jsonPath("$.receiverName").value("Dargent Dev LTDA"))
                .andExpect(jsonPath("$.receiverCity").value("SAO PAULO"));
    }

    @Test
    void creating_a_charge_with_bad_txid_returns_400_invalid_txid() throws Exception {
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("lowercase-and-too-long-xxxxxxxxxxxx", "10000", FUTURE, CALLBACK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_txid"));
    }

    @Test
    void creating_a_charge_with_non_positive_amount_returns_400_invalid_amount() throws Exception {
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BKD4Z9X2Q7W1M5T3R6Y0A1B2C", "0", FUTURE, CALLBACK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_amount"));
    }

    @Test
    void creating_a_charge_with_unparseable_expiry_returns_400_invalid_expiry() throws Exception {
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CKD4Z9X2Q7W1M5T3R6Y0A1B2C", "10000", "not-a-date", CALLBACK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_expiry"));
    }

    @Test
    void creating_a_charge_with_past_expiry_returns_400_invalid_expiry() throws Exception {
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DKD4Z9X2Q7W1M5T3R6Y0A1B2C", "10000", "2020-01-01T00:00:00Z", CALLBACK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_expiry"));
    }

    @Test
    void creating_a_charge_with_bad_callback_url_returns_400_invalid_callback_url() throws Exception {
        mockMvc.perform(post("/cobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EKD4Z9X2Q7W1M5T3R6Y0A1B2C", "10000", FUTURE, "not-a-url")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_callback_url"));
    }

    @Test
    void creating_a_duplicate_txid_returns_409_txid_already_exists() throws Exception {
        String txid = "FKD4Z9X2Q7W1M5T3R6Y0A1B2C";
        mockMvc.perform(post("/cobs").contentType(MediaType.APPLICATION_JSON)
                .content(body(txid, "10000", FUTURE, CALLBACK)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/cobs").contentType(MediaType.APPLICATION_JSON)
                .content(body(txid, "5000", FUTURE, CALLBACK)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("txid_already_exists"));
    }

    @Test
    void getting_an_open_charge_returns_200_with_open_status() throws Exception {
        String txid = "GKD4Z9X2Q7W1M5T3R6Y0A1B2C";
        mockMvc.perform(post("/cobs").contentType(MediaType.APPLICATION_JSON)
                .content(body(txid, "10000", FUTURE, CALLBACK)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/cobs/{txid}", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.expiresAt").value(FUTURE));
    }

    @Test
    void getting_an_expired_unpaid_charge_reports_status_expired() throws Exception {
        String txid = "HKD4Z9X2Q7W1M5T3R6Y0A1B2C";
        store.putIfAbsent(new Charge(txid, 10_000,
                Instant.parse("2020-01-01T00:00:00Z"), CALLBACK, "stale order"));

        mockMvc.perform(get("/cobs/{txid}", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void getting_an_unknown_txid_returns_404_cob_not_found() throws Exception {
        mockMvc.perform(get("/cobs/9XXXXXXXXX9XXXXXXXXX9XXXX"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("cob_not_found"))
                .andExpect(jsonPath("$.message").value(containsString("9XXXXXXXXX9XXXXXXXXX9XXXX")));
    }
}
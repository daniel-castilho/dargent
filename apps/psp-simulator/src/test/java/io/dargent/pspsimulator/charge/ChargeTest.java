package io.dargent.pspsimulator.charge;

import java.time.Instant;

import io.dargent.pspsimulator.error.PspApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeTest {

    private static final String TXID = "8KD4Z9X2Q7W1M5T3R6Y0A1B2C";
    private static final String CALLBACK = "http://api-blue:8080/webhooks/psp";

    private static Charge openCharge(long amount, Instant expiresAt) {
        return new Charge(TXID, amount, expiresAt, CALLBACK, "Order #123");
    }

    @Test
    void a_new_charge_is_open_before_expiry() {
        Charge charge = openCharge(10_000, Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(charge.statusFor(Instant.parse("2026-08-29T00:00:00Z"))).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void an_unpaid_charge_is_expired_when_now_is_past_expiry() {
        Charge charge = openCharge(10_000, Instant.parse("2026-08-29T00:30:00Z"));
        assertThat(charge.statusFor(Instant.parse("2026-08-29T00:30:01Z"))).isEqualTo(ChargeStatus.EXPIRED);
    }

    @Test
    void an_unpaid_charge_is_still_open_exactly_at_expiry() {
        Instant expiry = Instant.parse("2026-08-29T00:30:00Z");
        Charge charge = openCharge(10_000, expiry);
        assertThat(charge.statusFor(expiry)).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void a_paid_charge_stays_paid_forever_even_after_expiry() {
        Charge charge = openCharge(10_000, Instant.parse("2026-08-29T00:30:00Z"));
        charge.pay(Instant.parse("2026-08-29T00:15:00Z"), "E9040381234567890123456789012345",
                "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d");
        assertThat(charge.statusFor(Instant.parse("2030-01-01T00:00:00Z"))).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.status()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void paying_an_open_charge_stamps_paid_end_to_end_id_event_id_and_paid_at() {
        Charge charge = openCharge(10_000, Instant.parse("2026-08-29T00:30:00Z"));
        Instant paidAt = Instant.parse("2026-08-29T00:15:00Z");

        charge.pay(paidAt, "E9040381234567890123456789012345", "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d");

        assertThat(charge.status()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.endToEndId()).isEqualTo("E9040381234567890123456789012345");
        assertThat(charge.eventId()).isEqualTo("psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d");
        assertThat(charge.paidAt()).isEqualTo(paidAt);
    }

    @Test
    void paying_an_already_paid_charge_is_rejected_with_already_paid() {
        Charge charge = openCharge(10_000, Instant.parse("2026-08-29T00:30:00Z"));
        charge.pay(Instant.parse("2026-08-29T00:15:00Z"), "E9040381234567890123456789012345",
                "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d");

        assertThatThrownBy(() -> charge.pay(Instant.parse("2026-08-29T00:16:00Z"),
                "E9xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "psp-evt-5d1a4b2c-9e8f-4a3b-8c7d-6e5f4a3b2c1d"))
                .isInstanceOf(PspApiException.class)
                .satisfies(e -> {
                    PspApiException ex = (PspApiException) e;
                    assertThat(ex.getCode()).isEqualTo("already_paid");
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void paying_an_expired_charge_is_rejected_with_charge_expired() {
        Charge charge = openCharge(10_000, Instant.parse("2026-08-29T00:30:00Z"));

        assertThatThrownBy(() -> charge.pay(Instant.parse("2026-08-29T00:31:00Z"),
                "E9040381234567890123456789012345", "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d"))
                .isInstanceOf(PspApiException.class)
                .satisfies(e -> {
                    PspApiException ex = (PspApiException) e;
                    assertThat(ex.getCode()).isEqualTo("charge_expired");
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void charge_exposes_its_cob_fields() {
        Charge charge = openCharge(10_000, Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(charge.txid()).isEqualTo(TXID);
        assertThat(charge.amount()).isEqualTo(10_000L);
        assertThat(charge.callbackUrl()).isEqualTo(CALLBACK);
        assertThat(charge.description()).isEqualTo("Order #123");
    }
}
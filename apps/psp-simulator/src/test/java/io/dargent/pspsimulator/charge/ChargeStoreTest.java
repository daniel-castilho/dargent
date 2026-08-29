package io.dargent.pspsimulator.charge;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeStoreTest {

    private static final String TXID = "8KD4Z9X2Q7W1M5T3R6Y0A1B2C";

    private static Charge charge(String txid, Instant expiresAt) {
        return new Charge(txid, 10_000, expiresAt, "http://api-blue:8080/webhooks/psp", "Order #123");
    }

    @Test
    void inserting_a_new_txid_returns_null_and_then_stores_it() {
        ChargeStore store = new ChargeStore();
        assertThat(store.putIfAbsent(charge(TXID, Instant.parse("2030-01-01T00:00:00Z")))).isNull();
        assertThat(store.get(TXID)).isNotNull();
    }

    @Test
    void inserting_a_duplicate_txid_returns_the_existing_charge() {
        ChargeStore store = new ChargeStore();
        Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
        store.putIfAbsent(charge(TXID, expiry));

        Charge duplicate = charge(TXID, expiry);
        Charge existing = store.putIfAbsent(duplicate);

        assertThat(existing).isNotNull();
        assertThat(existing).isNotSameAs(duplicate);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void getting_an_unknown_txid_returns_null() {
        ChargeStore store = new ChargeStore();
        assertThat(store.get("9XXXXXXXXX9XXXXXXXXX9XXXX")).isNull();
    }
}
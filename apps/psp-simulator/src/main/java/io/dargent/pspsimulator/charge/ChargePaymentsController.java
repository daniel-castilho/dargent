package io.dargent.pspsimulator.charge;

import java.time.Clock;

import io.dargent.pspsimulator.error.PspApiException;
import io.dargent.pspsimulator.webhook.WebhookDispatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payer bank: {@code POST /cobs/{txid}/payments} (E2 spec §5.3). No body. Rule order is fixed —
 * unknown 404, already paid 409, expired 409, else pay and dispatch exactly one webhook. All the
 * banking rules live in {@link Charge#pay}; this controller only supplies the environment (clock and
 * the payer-bank id generators).
 */
@RestController
public class ChargePaymentsController {

    private final ChargeStore store;
    private final EndToEndIdGenerator endToEndIdGenerator;
    private final EventIdGenerator eventIdGenerator;
    private final Clock clock;
    private final WebhookDispatcher dispatcher;

    public ChargePaymentsController(ChargeStore store, EndToEndIdGenerator endToEndIdGenerator,
            EventIdGenerator eventIdGenerator, Clock clock, WebhookDispatcher dispatcher) {
        this.store = store;
        this.endToEndIdGenerator = endToEndIdGenerator;
        this.eventIdGenerator = eventIdGenerator;
        this.clock = clock;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/cobs/{txid}/payments")
    public PayChargeResponse pay(@PathVariable String txid) {
        Charge charge = store.get(txid);
        if (charge == null) {
            throw new PspApiException(404, "cob_not_found", "No charge with txid " + txid);
        }
        charge.pay(clock.instant(), endToEndIdGenerator.generate(), eventIdGenerator.generate());
        dispatcher.dispatch(charge);
        return PayChargeResponse.from(charge);
    }
}
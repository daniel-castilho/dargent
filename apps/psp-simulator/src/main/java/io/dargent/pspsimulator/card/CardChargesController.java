package io.dargent.pspsimulator.card;

import io.dargent.pspsimulator.charge.Charge;
import io.dargent.pspsimulator.charge.ChargeStore;
import io.dargent.pspsimulator.charge.EndToEndIdGenerator;
import io.dargent.pspsimulator.charge.EventIdGenerator;
import io.dargent.pspsimulator.charge.GetChargeResponse;
import io.dargent.pspsimulator.error.PspApiException;
import io.dargent.pspsimulator.webhook.WebhookDispatcher;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The instant card profile (M5 S1, D2 adjudication): {@code POST /card-charges} decides approval AT
 * create — an approved charge is born {@code PAID} and dispatches the SAME webhook engine/signer/
 * shape as PIX (the charge package's WebhookEvent); a declined charge answers {@code 402 card_declined} and is
 * never stored. The decline trigger is the magic amount in {@code PSP_CARD_DECLINE_AMOUNT}
 * (default 4444). This is the abstraction the platform's card rail consumes — deliberately NOT
 * card-network realism (no 3DS, no capture, no interchange).
 */
@RestController
@RequestMapping("/card-charges")
public class CardChargesController {

    private static final Pattern TXID = Pattern.compile("^[A-Z0-9]{25}$");
    private static final String CALLBACK_DESCRIPTION = "card-charge";
    private static final Duration CARD_LIFETIME = Duration.ofMinutes(5);

    private final ChargeStore store;
    private final Clock clock;
    private final EndToEndIdGenerator endToEndIdGenerator;
    private final EventIdGenerator eventIdGenerator;
    private final WebhookDispatcher dispatcher;
    private final long declineAmount;

    public CardChargesController(
            ChargeStore store,
            Clock clock,
            EndToEndIdGenerator endToEndIdGenerator,
            EventIdGenerator eventIdGenerator,
            WebhookDispatcher dispatcher,
            @Value("${PSP_CARD_DECLINE_AMOUNT:4444}") long declineAmount) {
        this.store = store;
        this.clock = clock;
        this.endToEndIdGenerator = endToEndIdGenerator;
        this.eventIdGenerator = eventIdGenerator;
        this.dispatcher = dispatcher;
        this.declineAmount = declineAmount;
    }

    @PostMapping
    public ResponseEntity<CardChargeResponse> create(@RequestBody CardChargeRequest request) {
        validate(request);
        if (request.amount() == declineAmount) {
            throw new PspApiException(402, "card_declined", "Card declined (magic amount " + declineAmount + ")");
        }
        Instant now = clock.instant();
        Charge charge = new Charge(
                request.txid(), request.amount(), now.plus(CARD_LIFETIME), request.callbackUrl(), CALLBACK_DESCRIPTION);
        Charge existing = store.putIfAbsent(charge);
        if (existing != null) {
            throw new PspApiException(409, "txid_already_exists", "A charge with txid " + request.txid() + " exists");
        }
        // Instant decision: born PAID, exactly one webhook, same engine as the payer-bank path.
        charge.pay(now, endToEndIdGenerator.generate(), eventIdGenerator.generate());
        dispatcher.dispatch(charge);
        return ResponseEntity.status(HttpStatus.CREATED).body(CardChargeResponse.from(charge));
    }

    @GetMapping("/{txid}")
    public GetChargeResponse get(@PathVariable String txid) {
        Charge charge = store.get(txid);
        if (charge == null) {
            throw new PspApiException(404, "card_charge_not_found", "No card charge with txid " + txid);
        }
        return GetChargeResponse.from(charge, clock.instant());
    }

    private void validate(CardChargeRequest request) {
        if (request.txid() == null || !TXID.matcher(request.txid()).matches()) {
            throw new PspApiException(400, "invalid_txid", "txid must be exactly 25 uppercase alphanumeric characters");
        }
        if (request.amount() <= 0) {
            throw new PspApiException(400, "invalid_amount", "amount must be a positive integer (cents)");
        }
        if (!isAbsoluteHttp(request.callbackUrl())) {
            throw new PspApiException(400, "invalid_callback_url", "callbackUrl must be an absolute http(s) URL");
        }
        if (request.token() == null || request.token().isBlank()) {
            throw new PspApiException(400, "invalid_token", "token (cardToken) is required");
        }
    }

    private static boolean isAbsoluteHttp(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

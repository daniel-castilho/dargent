package io.dargent.pspsimulator.charge;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import io.dargent.pspsimulator.config.PspProfile;
import io.dargent.pspsimulator.error.PspApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cobs (charges) API — E2 spec §5.1/§5.2. The merchant owns the txid: the simulator validates shape
 * and uniqueness but never invents charge ids (spec §3.1 lock #3).
 */
@RestController
@RequestMapping("/cobs")
public class ChargesController {

    private static final Pattern TXID = Pattern.compile("^[A-Z0-9]{25}$");

    private final ChargeStore store;
    private final Clock clock;
    private final PspProfile profile;

    public ChargesController(ChargeStore store, Clock clock, PspProfile profile) {
        this.store = store;
        this.clock = clock;
        this.profile = profile;
    }

    @PostMapping
    public ResponseEntity<CreateChargeResponse> create(@RequestBody CreateChargeRequest request) {
        validate(request);
        Charge charge = new Charge(request.txid(), request.amount(), parseExpiry(request.expiresAt()),
                request.callbackUrl(), request.description());
        Charge existing = store.putIfAbsent(charge);
        if (existing != null) {
            throw new PspApiException(409, "txid_already_exists", "A charge with txid " + request.txid() + " exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateChargeResponse.from(charge, profile));
    }

    @GetMapping("/{txid}")
    public GetChargeResponse get(@PathVariable String txid) {
        Charge charge = store.get(txid);
        if (charge == null) {
            throw new PspApiException(404, "cob_not_found", "No charge with txid " + txid);
        }
        return GetChargeResponse.from(charge, clock.instant());
    }

    private void validate(CreateChargeRequest request) {
        if (request.txid() == null || !TXID.matcher(request.txid()).matches()) {
            throw new PspApiException(400, "invalid_txid",
                    "txid must be exactly 25 uppercase alphanumeric characters");
        }
        if (request.amount() <= 0) {
            throw new PspApiException(400, "invalid_amount", "amount must be a positive integer (cents)");
        }
        Instant expiry = parseExpiry(request.expiresAt());
        if (!expiry.isAfter(clock.instant())) {
            throw new PspApiException(400, "invalid_expiry", "expiresAt must be a future RFC 3339 instant");
        }
        if (!isAbsoluteHttp(request.callbackUrl())) {
            throw new PspApiException(400, "invalid_callback_url", "callbackUrl must be an absolute http(s) URL");
        }
    }

    private static Instant parseExpiry(String raw) {
        if (raw == null) {
            throw new PspApiException(400, "invalid_expiry", "expiresAt is required (RFC 3339)");
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new PspApiException(400, "invalid_expiry", "expiresAt must be a parseable RFC 3339 instant");
        }
    }

    private static boolean isAbsoluteHttp(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
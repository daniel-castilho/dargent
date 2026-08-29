package io.dargent.pspsimulator.webhook;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.dargent.pspsimulator.config.WebhookSecret;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 signer for the webhook canonical string {@code timestamp + "." + rawBody}
 * (E2 spec §5.4). The documented test vector is asserted verbatim in {@code WebhookSignerTest}.
 * Lowercase hex output. This is an independent implementation of the shared specification — the
 * platform validates against the spec, never against this class (AGENTS.md §2).
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    @Autowired
    public WebhookSigner(WebhookSecret webhookSecret) {
        this.secret = webhookSecret.webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    WebhookSigner(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param timestamp Unix epoch seconds (delivery time)
     * @param rawBody the exact bytes that will be sent over the wire
     * @return lowercase-hex HMAC-SHA256 of the UTF-8 canonical string
     */
    public String sign(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
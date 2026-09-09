package io.dargent.payments.adapter.out.psp;

import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter.PspException;
import io.dargent.payments.application.PspDeclinedException;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PaymentRail;
import io.dargent.payments.domain.port.out.PspPort.ChargeResult;
import io.dargent.payments.domain.port.out.PspPort.CobStatus;
import io.dargent.payments.domain.port.out.PspPort.CreateChargeInput;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * PSP adapter for the E2 simulator's instant card profile (M5 S1, D2 adjudication): same transport
 * and retry posture as {@link SimulatorChargeAdapter} (JDK {@link HttpClient}, connect 2 s / read
 * 5 s, linear backoff, 5xx retryable) against {@code /card-charges}. Wire differences: the
 * approved charge is born {@code PAID} (no BR Code — {@link #presentment} is null), a
 * {@code 402 card_declined} maps to {@link PspDeclinedException}, and {@code 409} reads the charge
 * back. {@link #getCob} serves `GET /card-charges/{txid}` (same GetChargeResponse shape as PIX).
 */
public final class CardChargeAdapter implements PaymentRail {

    public static final String RAIL = "card";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Supplier<Long> sleeperMillis;
    private final ObjectMapper objectMapper;

    public CardChargeAdapter(String baseUrl, int maxAttempts, Duration baseBackoff, Supplier<Long> sleeperMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.sleeperMillis = sleeperMillis;
        // NO_PROXY selector mirroring SimulatorChargeAdapter: the PSP host is reached directly.
        ProxySelector noProxySelector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // no-op: connection failures surface through the retry policy
            }
        };
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(noProxySelector)
                .build();
        this.objectMapper = JsonMapper.builder().build();
    }

    @Override
    public String rail() {
        return RAIL;
    }

    @Override
    public String presentment(Txid txid, long amountCents) {
        return null;
    }

    @Override
    public ChargeResult createCharge(CreateChargeInput input) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String url = baseUrl + "/card-charges";
                String json = objectMapper.writeValueAsString(new CardChargeRequest(
                        input.txid().value(), input.amountCents(), input.callbackUrl(), input.cardToken()));
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                if (statusCode == 200 || statusCode == 201) {
                    CardChargeResponse body = objectMapper.readValue(response.body(), CardChargeResponse.class);
                    // The card charge has no expiry semantics: echo the requested one. The approved
                    // charge carries the payer-bank ids; no BR Code exists on this rail.
                    return new ChargeResult(new Txid(body.txid()), input.expiresAt(), body.endToEndId(), null);
                } else if (statusCode == 402) {
                    throw new PspDeclinedException("card_declined");
                } else if (statusCode == 409) {
                    return readBackCharge(input.txid(), input.expiresAt());
                } else if (!isRetryable(statusCode) || attempt == maxAttempts) {
                    throw new PspException(
                            "PSP card call failed with status " + statusCode + " after " + attempt + " attempts");
                }
            } catch (IOException | InterruptedException e) {
                if (!isRetryable(0) || attempt == maxAttempts) {
                    throw new PspException("PSP card call failed after " + attempt + " attempts", e);
                }
            }
            if (attempt < maxAttempts) {
                sleep(baseBackoff.multipliedBy(attempt));
            }
        }
        throw new PspException("PSP card call exhausted after " + maxAttempts + " attempts");
    }

    private ChargeResult readBackCharge(Txid txid, Instant expiresAt) {
        try {
            String url = baseUrl + "/card-charges/" + txid.value();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                CardChargeResponse body = objectMapper.readValue(response.body(), CardChargeResponse.class);
                return new ChargeResult(new Txid(body.txid()), expiresAt, body.endToEndId(), null);
            }
            throw new PspException(
                    "PSP card read-back failed with status " + response.statusCode() + " for txid " + txid.value());
        } catch (IOException | InterruptedException e) {
            throw new PspException("PSP card read-back failed for txid " + txid.value(), e);
        }
    }

    @Override
    public CobStatus getCob(Txid txid) {
        try {
            String url = baseUrl + "/card-charges/" + txid.value();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                CardChargeResponse cob = objectMapper.readValue(response.body(), CardChargeResponse.class);
                return new CobStatus(
                        new Txid(cob.txid()),
                        CobState.valueOf(cob.status()),
                        cob.amount(),
                        Instant.parse(cob.expiresAt()),
                        cob.endToEndId(),
                        cob.paidAt() == null ? null : Instant.parse(cob.paidAt()));
            } else if (response.statusCode() == 404) {
                throw new PspException("Card charge not found for txid " + txid.value());
            }
            throw new PspException(
                    "PSP card getCob failed with status " + response.statusCode() + " for txid " + txid.value());
        } catch (IOException | InterruptedException e) {
            throw new PspException("PSP card getCob failed for txid " + txid.value(), e);
        }
    }

    private boolean isRetryable(int statusCode) {
        return statusCode >= 500 || statusCode == 0;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(sleeperMillis.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PspException("Sleep interrupted", e);
        }
    }

    // Request/response records
    private record CardChargeRequest(String txid, long amount, String callbackUrl, String token) {}

    /** GET /card-charges/{txid} wire shape (same GetChargeResponse contract as PIX). */
    private record CardChargeResponse(
            String txid, String status, long amount, String expiresAt, String endToEndId, String paidAt) {}
}

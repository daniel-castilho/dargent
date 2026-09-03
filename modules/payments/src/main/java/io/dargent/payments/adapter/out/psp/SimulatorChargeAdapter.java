package io.dargent.payments.adapter.out.psp;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PspPort;
import java.io.IOException;
import java.net.URI;
import java.net.ProxySelector;
import java.net.Proxy;
import java.net.SocketAddress;
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
 * PSP adapter for the E2 simulator (E3 spec §5.7, §5.9): uses JDK {@link HttpClient} with
 * connect 2 s / read 5 s timeouts. Retry policy (D19): linear backoff via injected sleeper;
 * retryable = connect/read errors, 5xx; **409 {@code txid_already_exists} is NOT retryable** →
 * treated as already-created success path (read the charge back via {@code GET /cobs/{txid}}).
 */
public final class SimulatorChargeAdapter implements PspPort {

    private final java.net.http.HttpClient httpClient;
    private final String baseUrl;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Supplier<Long> sleeperMillis;
    private final ObjectMapper objectMapper;

    public SimulatorChargeAdapter(String baseUrl, int maxAttempts, Duration baseBackoff,
            Supplier<Long> sleeperMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.sleeperMillis = sleeperMillis;
        // NO_PROXY selector: the PSP host is reached directly (no corporate proxy hop); a plain
        // HttpClient.newBuilder() default would consult the JVM ProxySelector, and any ambient
        // http.proxy* system property could silently reroute PSP traffic through a broken proxy.
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
    public ChargeResult createCharge(CreateChargeInput input) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String url = baseUrl + "/cobs";
                String json = objectMapper.writeValueAsString(new ChargeRequest(input.txid().value(), input.amountCents(),
                        input.expiresAt().toString(), input.callbackUrl(), input.description()));
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                if (statusCode == 200 || statusCode == 201) {
                    var responseBody = objectMapper.readValue(response.body(), ChargeResponse.class);
                    return new ChargeResult(new Txid(responseBody.txid()), Instant.parse(responseBody.expiresAt()),
                            responseBody.endToEndId(), responseBody.brcode());
                } else if (statusCode == 409) {
                    return readBackCharge(input.txid());
                } else if (!isRetryable(statusCode) || attempt == maxAttempts) {
                    throw new PspException("PSP call failed with status " + statusCode + " after " + attempt + " attempts");
                }
            } catch (IOException | InterruptedException e) {
                if (!isRetryable(0) || attempt == maxAttempts) {
                    throw new PspException("PSP call failed after " + attempt + " attempts", e);
                }
            }
            if (attempt < maxAttempts) {
                sleep(baseBackoff.multipliedBy(attempt));
            }
        }
        throw new PspException("PSP exhausted after " + maxAttempts + " attempts");
    }

    private ChargeResult readBackCharge(Txid txid) {
        try {
            String url = baseUrl + "/cobs/" + txid.value();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var responseBody = objectMapper.readValue(response.body(), ChargeResponse.class);
                return new ChargeResult(new Txid(responseBody.txid()), Instant.parse(responseBody.expiresAt()),
                        responseBody.endToEndId(), responseBody.brcode());
            }
            throw new PspException("PSP read-back failed with status " + response.statusCode() + " for txid " + txid.value());
        } catch (IOException | InterruptedException e) {
            throw new PspException("PSP read-back failed for txid " + txid.value(), e);
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

    static class PspException extends RuntimeException {
        PspException(String msg, Throwable cause) {
            super(msg, cause);
        }
        PspException(String msg) {
            super(msg);
        }
    }

@Override
    public CobStatus getCob(Txid txid) {
        try {
            String url = baseUrl + "/cobs/" + txid.value();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var cob = objectMapper.readValue(response.body(), CobResponse.class);
                return new CobStatus(
                        new Txid(cob.txid()),
                        CobState.valueOf(cob.state()),
                        cob.amountCents(),
                        Instant.parse(cob.expiresAt()),
                        cob.endToEndId(),
                        cob.paidAt() == null ? null : Instant.parse(cob.paidAt())
                );
            } else if (response.statusCode() == 404) {
                throw new PspException("COB not found for txid " + txid.value());
            }
            throw new PspException("PSP getCob failed with status " + response.statusCode() + " for txid " + txid.value());
        } catch (IOException | InterruptedException e) {
            throw new PspException("PSP getCob failed for txid " + txid.value(), e);
        }
    }

    // Request/response records
    private record ChargeRequest(
            String txid,
            long amount,
            String expiresAt,
            String callbackUrl,
            String description
    ) {}

    private record ChargeResponse(
            String txid,
            String expiresAt,
            String endToEndId,
            String brcode
    ) {}

    private record CobResponse(
            String txid,
            String state,
            long amountCents,
            String expiresAt,
            String endToEndId,
            String paidAt
    ) {}
}
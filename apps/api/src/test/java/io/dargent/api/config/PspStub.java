package io.dargent.api.config;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

/**
 * Shared test PSP stub for boot-app ITs: serves POST /cobs with a canned PIX response so a real
 * create flows through the HTTP surface. Zero-wait sleeper (AGENTS §5.3 — no Thread.sleep in tests).
 */
public class PspStub {

    public enum Mode { SUCCESS, FAIL }

    public volatile Mode mode = Mode.SUCCESS;

    public void reset() {
        mode = Mode.SUCCESS;
    }

    public long sleeper() {
        return 0L;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        byte[] respBody;
        int status;
        try {
            if ("POST".equals(method) && "/cobs".equals(path)) {
                if (mode == Mode.FAIL) {
                    status = 500;
                    respBody = "{\"error\":\"internal\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String txid = extractTxid(requestBody);
                    respBody = ("{\"txid\":\"" + txid
+ "\",\"expiresAt\":\"2027-01-01T13:00:00Z\",\"endToEndId\":\"E2E-1\",\"brcode\":\"000201-terribly-long-brcode\"}")
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else if ("GET".equals(method) && path.startsWith("/cobs/")) {
                String txid = path.substring("/cobs/".length());
                status = 200;
                respBody = ("{\"txid\":\"" + txid
                        + "\",\"expiresAt\":\"2026-08-29T12:02:00Z\",\"endToEndId\":\"E2E-1\",\"brcode\":\"000201-terribly-long-brcode\"}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                respBody = "{}".getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            status = 500;
            respBody = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, respBody.length);
        exchange.getResponseBody().write(respBody);
        exchange.close();
    }

    private static String extractTxid(String body) {
        int i = body.indexOf("\"txid\"");
        int start = body.indexOf("\"", i + 7) + 1;
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }
}
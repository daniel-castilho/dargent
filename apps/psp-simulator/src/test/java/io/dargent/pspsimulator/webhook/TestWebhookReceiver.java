package io.dargent.pspsimulator.webhook;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Test double for the PSP's delivery target: a real HTTP receiver that captures the exact raw body
 * bytes and signature headers — so tests can recompute HMAC-SHA256 from what actually arrived (the
 * exact procedure the platform's intake must implement in E4). Registered as a bean by the tests
 * that need it; never in the production app.
 */
@RestController
@RequestMapping("/test-receiver/webhooks/psp")
public class TestWebhookReceiver {

    public record Captured(byte[] rawBody, String timestamp, String signature, String contentType) {
    }

    private final List<Captured> deliveries = new CopyOnWriteArrayList<>();

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public void receive(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        deliveries.add(new Captured(body,
                request.getHeader("X-PSP-Timestamp"),
                request.getHeader("X-PSP-Signature"),
                request.getContentType()));
    }

    public List<Captured> captured() {
        return List.copyOf(deliveries);
    }

    public void clear() {
        deliveries.clear();
    }
}
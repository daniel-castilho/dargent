package io.dargent.pspsimulator.webhook;

import java.time.Clock;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;
import io.dargent.pspsimulator.charge.Charge;
import io.dargent.pspsimulator.config.ChaosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The signed webhook delivery engine (E2 spec §5.4): serialize the event once, sign the exact bytes,
 * POST them async to the charge's callbackUrl with {@code X-PSP-Timestamp}/{@code X-PSP-Signature}/
 * {@code Content-Type: application/json}. Single attempt, no retry — the receiver's response status
 * is ignored and failures are logged (WARN) and dropped; the reconciler is the recovery story (E5),
 * not a retry loop.
 *
 * <p>Executor: bounded pool sized 4 (workers), unbounded queue — documented in the failure playbook.
 * Threads are daemon so a test context can never leak a JVM. Delivery-side chaos (spec §6) order per
 * delivery: {@code delay} (shared scheduler — schedule-based, never a sleep-per-thread) → {@code drop}
 * (seedable {@link Random}, forced extremes are deterministic) → {@code duplicate} (dispatch enqueues
 * two copies of the SAME event). Request-side knobs (error-rate/latency) live in {@link ChaosFilter}.
 */
@Component
public class AsyncWebhookDispatcher implements WebhookDispatcher, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AsyncWebhookDispatcher.class);

    private final WebhookSigner signer;
    private final ObjectMapper mapper;
    private final ChaosProperties chaos;
    private final Random random;
    private final Clock clock;
    private final RestClient restClient;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public AsyncWebhookDispatcher(WebhookSigner signer, ObjectMapper mapper, ChaosProperties chaos,
            Random random, Clock clock) {
        this.signer = signer;
        this.mapper = mapper;
        this.chaos = chaos;
        this.random = random;
        this.clock = clock;
        this.restClient = RestClient.builder().requestFactory(clientFactory()).build();
        this.executor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), deliveryThreadFactory("webhook-delivery"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                deliveryThreadFactory("webhook-scheduler"));
    }

    @Override
    public void dispatch(Charge charge) {
        int copies = chaos.isWebhookDuplicate() ? 2 : 1;
        for (int copy = 0; copy < copies; copy++) {
            scheduleDelivery(charge);
        }
    }

    /**
     * Per-delivery chaos order (spec §6): {@code delay} first (schedule-based), then {@code drop}
     * (seedable Random; forced extremes deterministic), then the send itself.
     */
    void scheduleDelivery(Charge charge) {
        int delayMs = chaos.getWebhookDelayMs();
        if (delayMs > 0) {
            scheduler.schedule(() -> maybeDeliver(charge), delayMs, TimeUnit.MILLISECONDS);
        } else {
            executor.execute(() -> maybeDeliver(charge));
        }
    }

    private void maybeDeliver(Charge charge) {
        if (chaos.getWebhookDropRate() > 0.0 && random.nextDouble() < chaos.getWebhookDropRate()) {
            log.debug("webhook dropped by chaos knob for txid {}", charge.txid());
            return;
        }
        attemptDelivery(charge);
    }

    private void attemptDelivery(Charge charge) {
        byte[] body = WebhookEvent.of(charge).toJsonBytes(mapper);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = signer.sign(timestamp, body);
        log.debug("delivering webhook for txid {} to {}", charge.txid(), charge.callbackUrl());
        try {
            restClient.post()
                    .uri(charge.callbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-PSP-Timestamp", timestamp)
                    .header("X-PSP-Signature", signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("webhook delivery failed for txid {}: {}", charge.txid(), e.getMessage());
        }
    }

    private static SimpleClientHttpRequestFactory clientFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    private static ThreadFactory deliveryThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }
}
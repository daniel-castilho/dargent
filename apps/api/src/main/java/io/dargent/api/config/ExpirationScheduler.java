package io.dargent.api.config;

import io.dargent.payments.application.ExpirationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * SmartLifecycle for the expiration scheduler.
 * <p>
 * The actual fixed-delay scheduling is driven by {@link ThreadPoolTaskScheduler}
 * in the composition config. This lifecycle only exposes {@link #runOnce()} for
 * deterministic IT execution (spec §7.1). No background thread is started here
 * to avoid racing with Flyway migrations during context refresh.
 */
public class ExpirationScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExpirationScheduler.class);

    private final ExpirationUseCase useCase;
    private final int batch;
    private volatile boolean running = false;

    public ExpirationScheduler(ExpirationUseCase useCase, int batch) {
        this.useCase = useCase;
        this.batch = batch;
    }

    @Override
    public void start() {
        running = true;
        log.info("Expiration scheduler started (batch={})", batch);
    }

    @Override
    public void stop() {
        running = false;
        log.info("Expiration scheduler stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Runs one scheduler cycle — called by TaskScheduler or directly by ITs. */
    public int runOnce() {
        if (!running) {
            return 0;
        }
        try {
            return useCase.runOnce(batch);
        } catch (Exception e) {
            log.error("Expiration scheduler error: {}", e.getMessage(), e);
            return 0;
        }
    }
}
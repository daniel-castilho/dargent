package io.dargent.api.config;

import io.dargent.payments.application.ReconciliationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * SmartLifecycle for the reconciler.
 * <p>
 * The actual fixed-delay scheduling is driven by {@link ThreadPoolTaskScheduler}
 * in the composition config. This lifecycle only exposes {@link #runOnce()} for
 * deterministic IT execution (spec §7.2). No background thread is started here
 * to avoid racing with Flyway migrations during context refresh.
 */
public class ReconciliationScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationUseCase useCase;
    private final int batch;
    private volatile boolean running = false;

    public ReconciliationScheduler(ReconciliationUseCase useCase, int batch) {
        this.useCase = useCase;
        this.batch = batch;
    }

    @Override
    public void start() {
        running = true;
        log.info("Reconciler started (batch={})", batch);
    }

    @Override
    public void stop() {
        running = false;
        log.info("Reconciler stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Runs one reconciliation cycle — called by TaskScheduler or directly by ITs. */
    public int runOnce() {
        if (!running) {
            return 0;
        }
        try {
            return useCase.runOnce(batch);
        } catch (Exception e) {
            log.error("Reconciler error: {}", e.getMessage(), e);
            return 0;
        }
    }
}
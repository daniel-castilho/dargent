package io.dargent.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * SmartLifecycle wrapper that runs the {@link JournalCoverageAuditor} scan once per configured
 * interval, mirroring {@link ReconciliationScheduler}. Composition-root only.
 */
public final class JournalCoverageScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JournalCoverageScheduler.class);

    private final JournalCoverageAuditor auditor;
    private volatile boolean running;
    private volatile boolean runNext = true;

    public JournalCoverageScheduler(JournalCoverageAuditor auditor) {
        this.auditor = auditor;
    }

    @Override
    public void start() {
        this.running = true;
        log.info("Journal coverage scheduler started");
    }

    @Override
    public void stop() {
        this.running = false;
        log.info("Journal coverage scheduler stopped");
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    /** Single deterministic scan (used by the fixed-delay task and by tests). Returns gap count. */
    public int runOnce() {
        if (!runNext) {
            return 0;
        }
        runNext = false;
        return auditor.runOnce();
    }
}
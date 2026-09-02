package io.dargent.api.config;

import io.dargent.notifications.adapter.out.messaging.SqsNotificationConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * SmartLifecycle implementation for the notifications SQS consumer.
 * Runs a polling loop that delegates to {@link SqsNotificationConsumer#runOnce()}.
 */
public class NotificationsConsumerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationsConsumerLifecycle.class);
    private final SqsNotificationConsumer consumer;
    private volatile boolean running = false;

    public NotificationsConsumerLifecycle(SqsNotificationConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public void start() {
        running = true;
        new Thread(new ConsumerRunner()).start();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Runs one consumer cycle — delegated by the scheduler. */
    public int runOnce() {
        return consumer.runOnce();
    }

    private class ConsumerRunner implements Runnable {
        @Override
        public void run() {
            log.info("Notifications SQS consumer started");
            while (running && !Thread.currentThread().isInterrupted()) {
                int processed = 0;
                try {
                    processed = consumer.runOnce();
                } catch (Exception e) {
                    log.error("Notifications consumer error: {}", e.getMessage(), e);
                }
                if (processed == 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                log.info("Notifications SQS consumer stopped");
            }
        }
    }
}
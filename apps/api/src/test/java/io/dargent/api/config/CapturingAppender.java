package io.dargent.api.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Logback appender that captures log events in memory for testing.
 * Add to logback-test.xml or programmatically register.
 */
public class CapturingAppender extends AppenderBase<ILoggingEvent> {

    private static final List<ILoggingEvent> EVENTS = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        // Snapshot threadName + MDC on the emitting thread (the AsyncAppender pattern).
        // Without this the event stays lazy: a post-hoc encoder reading it from another
        // thread would see the ASSERTING thread's name/empty MDC instead of the request's.
        event.prepareForDeferredProcessing();
        EVENTS.add(event);
    }

    public static List<ILoggingEvent> getEvents() {
        return new ArrayList<>(EVENTS);
    }

    public static void clear() {
        EVENTS.clear();
    }
}
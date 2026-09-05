package io.dargent.api.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Test-side ECS wire-format capture (E11 S1 remediation).
 *
 * Boot's {@code logging.structured.format.console=ecs} uses a {@link StructuredLogEncoder} that is
 * wired into the console appender at boot and is not reachable from a {@code ListAppender}, which
 * captures pre-encoding {@link ILoggingEvent}s. To prove the WIRE format we re-encode captured
 * events through Boot's own {@code StructuredLogEncoder} with {@code format=ecs} — the same encoder
 * and formatter the console uses. Events are captured from the appender list; this utility formats
 * them just like the console line, so assertions run against the real ECS byte output.
 *
 * <p>The encoder needs the Spring {@link Environment} installed in the Logback context (Boot puts it
 * there when it builds the encoder). {@link #registerSpringEnvironment(Environment)} lets an IT hand
 * its Spring {@code Environment} over so the encoder can be constructed lazily on first format.
 *
 * <p>Thread model: the encoder instance is created once and reused; {@link #formatAll(List)} is
 * synchronized because {@code StructuredLogEncoder} is not documented thread-safe and the
 * emitting thread differs from the asserting thread.
 */
public final class EcsLogContext {

    private static final StructuredLogEncoder ENCODER = new StructuredLogEncoder();
    private static volatile boolean environmentReady;
    private static boolean started;

    private EcsLogContext() {
    }

    /**
     * Hands the Spring {@link Environment} to the Logback context so the ECS encoder can be built.
     * Safe to call more than once; a no-op after the first success.
     */
    public static synchronized void registerSpringEnvironment(Environment env) {
        if (environmentReady) {
            return;
        }
        org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext ctx) {
            ctx.putObject(Environment.class.getName(), env);
        }
        environmentReady = true;
    }

    /**
     * Lazy register from a plain map when no Spring {@code Environment} is handy (unit-shape probes).
     */
    public static synchronized void registerEnvFromMap(String appName) {
        if (environmentReady) {
            return;
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("ecs-register",
                Map.of("spring.application.name", appName)));
        org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext ctx) {
            ctx.putObject(Environment.class.getName(), env);
        }
        environmentReady = true;
    }

    /**
     * Re-encodes captured events through Boot's ECS encoder, returning one wire-formatted line each.
     * Runs the format under {@code synchronized} so a single shared encoder is never raced.
     */
    public static synchronized List<String> formatEcs(List<? extends ILoggingEvent> events) {
        ensureStarted();
        List<String> out = new ArrayList<>(events.size());
        for (ILoggingEvent event : events) {
            byte[] bytes = ENCODER.encode(event);
            out.add(new String(bytes, StandardCharsets.UTF_8).trim());
        }
        return out;
    }

    /**
     * Returns a single ECS-formatted line for one captured event (handy for a single assertion).
     */
    public static synchronized String formatEcs(ILoggingEvent event) {
        ensureStarted();
        byte[] bytes = ENCODER.encode(event);
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    /**
     * Safe to call once the register hooks are done. Builds the encoder's formatter against the
     * Spring Environment that is now installed in the Logback context. Starts only once; later
     * calls are no-ops.
     */
    private static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        ENCODER.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        ENCODER.setFormat("ecs");
        ENCODER.start();
        started = true;
    }

    /**
     * True if the Spring Environment has been registered (so the encoder will not fail at start).
     */
    public static synchronized boolean isEnvironmentReady() {
        return environmentReady;
    }

    /** Last known winner of the encoder's state (for diagnostics only). */
    public static synchronized String diagnostics() {
        return environmentReady ? "ecs-encoder-ready" : "ecs-encoder-environment-not-ready";
    }
}
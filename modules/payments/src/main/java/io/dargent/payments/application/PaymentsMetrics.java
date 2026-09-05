package io.dargent.payments.application;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * E11 §5 metric contract for the payments counters (names FROZEN from observability.md §3).
 *
 * <p>Spring-free holder that maps the frozen Prometheus names to Micrometer meters on a shared
 * {@link MeterRegistry}. Domain stays meter-free; these increments run at use-case level where the
 * counter IS a domain outcome (spec §5, "use-case-level injection is sanctioned"). Outbox lag and
 * DLQ depth gauges live in the payments adapters, not here.
 */
public final class PaymentsMetrics {

    public static final String TRANSITIONS = "dargent.payments.transitions";
    public static final String OUTBOX_ATTEMPTS = "dargent.outbox.attempts";
    public static final String RECONCILER_CONFIRMATIONS = "dargent.reconciler.confirmations";
    public static final String IDEMPOTENCY_EVENTS = "dargent.idempotency.events";
    public static final String REFUNDS_REJECTED = "dargent.refunds.rejected";

    private final MeterRegistry registry;

    public PaymentsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Payment state transition. {@code from} is {@code "none"} for the creation transition. */
    public void transition(String from, String to, String outcome) {
        registry.counter(TRANSITIONS, "from", from, "to", to, "outcome", outcome).increment();
    }

    /** Relay mark path result: {@code sent} | {@code failed} | {@code exhausted}. */
    public void outboxAttempt(String result) {
        registry.counter(OUTBOX_ATTEMPTS, "result", result).increment();
    }

    /** Reconciliation confirm outcome: {@code confirm} | {@code resurrect}. */
    public void reconcilerConfirmation(String outcome) {
        registry.counter(RECONCILER_CONFIRMATIONS, "outcome", outcome).increment();
    }

    /** Create idempotency outcome: {@code replayed} | {@code conflict} | {@code in_flight}. */
    public void idempotencyEvent(String kind) {
        registry.counter(IDEMPOTENCY_EVENTS, "kind", kind).increment();
    }

    /** Refund rejection code: {@code not_refundable} | {@code exceeds_remaining}. */
    public void refundRejected(String code) {
        registry.counter(REFUNDS_REJECTED, "code", code).increment();
    }
}
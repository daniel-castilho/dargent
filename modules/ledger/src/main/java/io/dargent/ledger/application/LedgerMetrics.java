package io.dargent.ledger.application;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * E12 N8 metric contract for the ledger proof counter (name FROZEN from observability.md §3).
 *
 * <p>Spring-free holder mapping the frozen Prometheus name to a Micrometer counter, mirroring the
 * payments {@code PaymentsMetrics} pattern (use-case-level injection is sanctioned; the counter IS
 * a domain outcome). Both {@code scope} vocabularies are PRE-REGISTERED at construction so the
 * series is present at 0 on every scrape — presence is the signal, failures are never seeded in
 * tests (AGENTS §5: a seeded proof failure would fake the guarantee the series guards).
 *
 * <p>Scope vocabulary (frozen):
 * <ul>
 *   <li>{@code balance} — global Σ DEBIT ≠ Σ CREDIT (the journal does not balance)</li>
 *   <li>{@code projection} — balances projection ≠ Σ postings per account, or a journal entry
 *       carries fewer than 2 postings (the projection is not the journal)</li>
 * </ul>
 */
public final class LedgerMetrics {

    public static final String PROOF_FAIL = "dargent.ledger.proof.fail";
    public static final String SCOPE_BALANCE = "balance";
    public static final String SCOPE_PROJECTION = "projection";

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Pre-register both scopes: the series must exist at 0 on a healthy system.
        registry.counter(PROOF_FAIL, "scope", SCOPE_BALANCE);
        registry.counter(PROOF_FAIL, "scope", SCOPE_PROJECTION);
    }

    /** Proof failure: {@code balance} (Σ DR ≠ Σ CR) or {@code projection} (projection ≠ lines). */
    public void proofFail(String scope) {
        registry.counter(PROOF_FAIL, "scope", scope).increment();
    }
}

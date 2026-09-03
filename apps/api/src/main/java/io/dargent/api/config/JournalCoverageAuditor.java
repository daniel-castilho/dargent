package io.dargent.api.config;

import io.dargent.payments.domain.port.out.AuditWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Journal coverage auditor (E5 spec §6, DEBT-4) — composition-root only, so it may read both
 * {@code payments} and {@code ledger} schemas without violating the module-boundary rules.
 * Detect-and-alarm only; it never auto-repairs.
 * <p>
 * Per scan it detects two gap directions:
 * <ul>
 *   <li><b>Phase A</b> — a CONFIRMED payment (in {@code payments.payments}) with no corresponding
 *       POSTED {@code payment.confirmed} {@code ledger.events} row. {@link #PHASE_A}.</li>
 *   <li><b>Phase B</b> — a POSTED {@code payment.confirmed} {@code ledger.events} row with no
 *       CONFIRMED payment. {@link #PHASE_B}.</li>
 * </ul>
 * Each gap is a {@link #coverageGap(String, UUID, String, String)} WARN log + a payments audit row
 * {@code command_name='journal_coverage_gap'}, details in the {@code request_id} column (no schema
 * change). The txid is stored in {@code aggregate_id} and the merchant in {@code merchant_id}.
 * <p>
 * The two directions are computed by <em>two separate per-schema queries</em> with the missing-set
 * diff in Java — no cross-schema JOIN (AGENTS §2.4 / design.md §3 "zero JOIN across schemas").
 */
public final class JournalCoverageAuditor {

    private static final Logger log = LoggerFactory.getLogger(JournalCoverageAuditor.class);
    private static final String GAP_COMMAND = "journal_coverage_gap";
    private static final String PHASE_A = "PHASE_A";
    private static final String PHASE_B = "PHASE_B";

    private final JdbcClient jdbc;
    private final AuditWriter auditWriter;

    public JournalCoverageAuditor(JdbcClient jdbc, AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

    /**
     * Scans both schemas once and reports (WARN + audit) any gap.
     *
     * @return number of distinct gaps detected (0 == clean)
     */
    public int runOnce() {
        Map<String, UUID> confirmed = jdbc.sql("""
                select txid, merchant_id from payments.payments where status = 'CONFIRMED'
                """)
                .query((rs, i) -> new Object[]{rs.getString("txid"), rs.getObject("merchant_id", UUID.class)})
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row[0], row -> (UUID) row[1], (a, b) -> a));
        Map<String, UUID> posted = jdbc.sql("""
                select txid, merchant_id from ledger.events
                where status = 'POSTED' and type = 'payment.confirmed'
                """)
                .query((rs, i) -> new Object[]{rs.getString("txid"), rs.getObject("merchant_id", UUID.class)})
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row[0], row -> (UUID) row[1], (a, b) -> a));

        int gaps = 0;
        // Phase A: confirmed payment without a posted journal event.
        for (Map.Entry<String, UUID> e : confirmed.entrySet()) {
            if (!posted.containsKey(e.getKey())) {
                coverageGap(PHASE_A, e.getValue(), e.getKey(), "confirmed payment has no POSTED payment.confirmed journal event");
                gaps++;
            }
        }
        // Phase B: posted journal event without a confirmed payment.
        for (Map.Entry<String, UUID> e : posted.entrySet()) {
            if (!confirmed.containsKey(e.getKey())) {
                coverageGap(PHASE_B, e.getValue(), e.getKey(), "POSTED payment.confirmed journal event has no CONFIRMED payment");
                gaps++;
            }
        }
        return gaps;
    }

    private void coverageGap(String phase, UUID merchantId, String txid, String detail) {
        log.warn("Journal coverage gap {} txid={} merchant={}: {}", phase, txid, merchantId, detail);
        auditWriter.record(GAP_COMMAND, null, merchantId, txid, phase + ":" + txid);
    }
}
package io.dargent.api.config;

import io.dargent.payments.domain.port.out.AuditWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Journal coverage auditor (E5 spec §6, DEBT-4) — composition-root only, so it may read both
 * {@code payments} and {@code ledger} schemas without violating the module-boundary rules.
 * Detect-and-alarm only; it never auto-repairs.
 * <p>
 * Per scan it detects two gap directions, plus two refund-coverage legs (E8 S7, spec §6):
 * <ul>
 *   <li><b>Phase A</b> — a CONFIRMED payment (in {@code payments.payments}) with no corresponding
 *       POSTED {@code payment.confirmed} {@code ledger.events} row. {@link #PHASE_A}.</li>
 *   <li><b>Phase B</b> — a POSTED {@code payment.confirmed} {@code ledger.events} row with no
 *       CONFIRMED payment. {@link #PHASE_B}.</li>
 *   <li><b>Phase C</b> — a refunded payment (a row in {@code payments.refunds}) with no POSTED
 *       {@code refund.created} {@code ledger.events} row. {@link #PHASE_C}.</li>
 *   <li><b>Phase D</b> — a POSTED {@code refund.created} {@code ledger.events} row with no
 *       corresponding refunded payment. {@link #PHASE_D}.</li>
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
    private static final String PHASE_C = "PHASE_C";
    private static final String PHASE_D = "PHASE_D";

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
        // Materialize each query into a List BEFORE folding into the maps. JdbcClient's lazy
        // .stream() is backed by an open connection/ResultSet; draining it with .collect(Collectors...)
        // without closing leaks a connection per query per scan (Hikari exhaustion under the audit IT).
        // .list() is a terminal op that closes the stream, so no connection leaks.
        List<Object[]> confirmedRows = jdbc.sql("""
                select txid, merchant_id from payments.payments where status = 'CONFIRMED'
                """)
                .query((rs, i) -> new Object[]{rs.getString("txid"), rs.getObject("merchant_id", UUID.class)})
                .list();
        Map<String, UUID> confirmed = indexByTxid(confirmedRows);

        List<Object[]> postedRows = jdbc.sql("""
                select txid, merchant_id from ledger.events
                where status = 'POSTED' and type = 'payment.confirmed'
                """)
                .query((rs, i) -> new Object[]{rs.getString("txid"), rs.getObject("merchant_id", UUID.class)})
                .list();
        Map<String, UUID> posted = indexByTxid(postedRows);

        // Refund coverage (E8 S7, spec §6 legs (c)/(d)): refunds recorded on the payments side must
        // have a matching POSTED refund.created journal event. The payments-internal JOIN
        // (refunds -> payments) is same-schema, so it does not violate AGENTS §2.4.
        List<Object[]> refundedRows = jdbc.sql("""
                select r.txid, p.merchant_id
                from payments.refunds r
                join payments.payments p on p.id = r.payment_id
                """)
                .query((rs, i) -> new Object[]{rs.getString("txid"), rs.getObject("merchant_id", UUID.class)})
                .list();
        Map<String, UUID> refunded = indexByTxid(refundedRows);

        List<Object[]> postedRefundRows = jdbc.sql("""
                select txid, merchant_id from ledger.events
                where status = 'POSTED' and type = 'refund.created'
                """)
                .query((rs, i) -> new Object[]{rs.getString("txid"), rs.getObject("merchant_id", UUID.class)})
                .list();
        Map<String, UUID> postedRefunds = indexByTxid(postedRefundRows);

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

        // Phase C: refunded payment (a refund row exists) with no POSTED refund.created journal event.
        for (Map.Entry<String, UUID> e : refunded.entrySet()) {
            if (!postedRefunds.containsKey(e.getKey())) {
                coverageGap(PHASE_C, e.getValue(), e.getKey(), "refunded payment has no POSTED refund.created journal event");
                gaps++;
            }
        }
        // Phase D: POSTED refund.created journal event with no corresponding refunded payment.
        for (Map.Entry<String, UUID> e : postedRefunds.entrySet()) {
            if (!refunded.containsKey(e.getKey())) {
                coverageGap(PHASE_D, e.getValue(), e.getKey(), "POSTED refund.created journal event has no matching refunded payment");
                gaps++;
            }
        }
        return gaps;
    }

    private void coverageGap(String phase, UUID merchantId, String txid, String detail) {
        log.warn("Journal coverage gap {} txid={} merchant={}: {}", phase, txid, merchantId, detail);
        auditWriter.record(GAP_COMMAND, null, merchantId, txid, phase + ":" + txid);
    }

    private static Map<String, UUID> indexByTxid(List<Object[]> rows) {
        Map<String, UUID> index = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            index.putIfAbsent((String) row[0], (UUID) row[1]);
        }
        return index;
    }
}
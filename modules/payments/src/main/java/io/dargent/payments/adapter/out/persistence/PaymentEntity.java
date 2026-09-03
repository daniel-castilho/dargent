package io.dargent.payments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistence model for {@code payments.payments} (design.md §5.1, spec §8) —
 * lives ONLY in the adapter package (D14); the domain aggregate is a separate,
 * framework-free class. {@code version} is a {@link jakarta.persistence.Version}
 * column: Hibernate re-imposes the optimistic guard at the DB row level (D6).
 */
@Entity
@Table(name = "payments", schema = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "txid", nullable = false, unique = true, length = 25)
    private String txid;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "description", length = 140)
    private String description;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "next_reconcile_at")
    private Instant nextReconcileAt;

    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column(name = "end_to_end_id", length = 32)
    private String endToEndId;

    @Column(name = "fee_cents")
    private Long feeCents;

    @Column(name = "net_cents")
    private Long netCents;

    @Column(name = "late_confirmation", nullable = false)
    private boolean lateConfirmation;

    @Column(name = "refunded_cents", nullable = false)
    private long refundedCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTxid() {
        return txid;
    }

    public void setTxid(String txid) {
        this.txid = txid;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public void setEndToEndId(String endToEndId) {
        this.endToEndId = endToEndId;
    }

    public Long getFeeCents() {
        return feeCents;
    }

    public void setFeeCents(Long feeCents) {
        this.feeCents = feeCents;
    }

    public Long getNetCents() {
        return netCents;
    }

    public void setNetCents(Long netCents) {
        this.netCents = netCents;
    }

    public boolean isLateConfirmation() {
        return lateConfirmation;
    }

    public void setLateConfirmation(boolean lateConfirmation) {
        this.lateConfirmation = lateConfirmation;
    }

    public long getRefundedCents() {
        return refundedCents;
    }

    public void setRefundedCents(long refundedCents) {
        this.refundedCents = refundedCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getNextReconcileAt() {
        return nextReconcileAt;
    }

    public void setNextReconcileAt(Instant nextReconcileAt) {
        this.nextReconcileAt = nextReconcileAt;
    }

    public int getReconcileAttempts() {
        return reconcileAttempts;
    }

    public void setReconcileAttempts(int reconcileAttempts) {
        this.reconcileAttempts = reconcileAttempts;
    }
}
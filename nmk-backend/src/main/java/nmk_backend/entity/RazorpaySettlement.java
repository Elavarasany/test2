package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "razorpay_settlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_razorpay_settlement_provider_id",
                columnNames = "provider_settlement_id"
        ),
        indexes = {
                @Index(name = "idx_razorpay_settlement_status_date", columnList = "status, provider_created_at"),
                @Index(name = "idx_razorpay_settlement_reconciliation", columnList = "reconciliation_status, last_synced_at")
        }
)
public class RazorpaySettlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_settlement_id", nullable = false, length = 64)
    private String providerSettlementId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "fees_paise", nullable = false)
    private long feesPaise;

    @Column(name = "tax_paise", nullable = false)
    private long taxPaise;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(length = 100)
    private String utr;

    @Column(name = "provider_created_at")
    private LocalDateTime providerCreatedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @Column(name = "reconciliation_status", nullable = false, length = 30)
    private String reconciliationStatus = "PENDING";

    @Column(name = "total_entries", nullable = false)
    private int totalEntries;

    @Column(name = "matched_entries", nullable = false)
    private int matchedEntries;

    @Column(name = "unmatched_entries", nullable = false)
    private int unmatchedEntries;

    @Column(name = "manual_review_entries", nullable = false)
    private int manualReviewEntries;

    @Column(name = "pending_entries", nullable = false)
    private int pendingEntries;

    @Column(name = "net_rows_paise", nullable = false)
    private long netRowsPaise;

    @Column(name = "totals_balanced", nullable = false)
    private boolean totalsBalanced;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @PrePersist
    @PreUpdate
    void updateSyncTime() {
        lastSyncedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getProviderSettlementId() { return providerSettlementId; }
    public void setProviderSettlementId(String value) { providerSettlementId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long value) { amountPaise = value; }
    public long getFeesPaise() { return feesPaise; }
    public void setFeesPaise(long value) { feesPaise = value; }
    public long getTaxPaise() { return taxPaise; }
    public void setTaxPaise(long value) { taxPaise = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getUtr() { return utr; }
    public void setUtr(String value) { utr = value; }
    public LocalDateTime getProviderCreatedAt() { return providerCreatedAt; }
    public void setProviderCreatedAt(LocalDateTime value) { providerCreatedAt = value; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime value) { settledAt = value; }
    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public String getReconciliationStatus() { return reconciliationStatus; }
    public void setReconciliationStatus(String value) { reconciliationStatus = value; }
    public int getTotalEntries() { return totalEntries; }
    public void setTotalEntries(int value) { totalEntries = value; }
    public int getMatchedEntries() { return matchedEntries; }
    public void setMatchedEntries(int value) { matchedEntries = value; }
    public int getUnmatchedEntries() { return unmatchedEntries; }
    public void setUnmatchedEntries(int value) { unmatchedEntries = value; }
    public int getManualReviewEntries() { return manualReviewEntries; }
    public void setManualReviewEntries(int value) { manualReviewEntries = value; }
    public int getPendingEntries() { return pendingEntries; }
    public void setPendingEntries(int value) { pendingEntries = value; }
    public long getNetRowsPaise() { return netRowsPaise; }
    public void setNetRowsPaise(long value) { netRowsPaise = value; }
    public boolean isTotalsBalanced() { return totalsBalanced; }
    public void setTotalsBalanced(boolean value) { totalsBalanced = value; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String value) { rawPayload = value; }
}

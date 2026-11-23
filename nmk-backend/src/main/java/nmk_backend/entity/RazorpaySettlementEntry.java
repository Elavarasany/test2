package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "razorpay_settlement_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_razorpay_settlement_entry",
                columnNames = {"settlement_id", "provider_entity_id", "entry_type"}
        ),
        indexes = {
                @Index(name = "idx_settlement_entry_payment", columnList = "payment_id"),
                @Index(name = "idx_settlement_entry_order", columnList = "order_id"),
                @Index(name = "idx_settlement_entry_match", columnList = "match_status, settlement_id")
        }
)
public class RazorpaySettlementEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    private RazorpaySettlement settlement;

    @Column(name = "provider_entity_id", nullable = false, length = 64)
    private String providerEntityId;

    @Column(name = "entry_type", nullable = false, length = 30)
    private String entryType;

    @Column(name = "debit_paise", nullable = false)
    private long debitPaise;

    @Column(name = "credit_paise", nullable = false)
    private long creditPaise;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "fee_paise", nullable = false)
    private long feePaise;

    @Column(name = "tax_paise", nullable = false)
    private long taxPaise;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "on_hold", nullable = false)
    private boolean onHold;

    @Column(nullable = false)
    private boolean settled;

    @Column(name = "provider_created_at")
    private LocalDateTime providerCreatedAt;

    @Column(name = "provider_settled_at")
    private LocalDateTime providerSettledAt;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "settlement_utr", length = 100)
    private String settlementUtr;

    @Column(length = 30)
    private String method;

    @Column(name = "dispute_id", length = 64)
    private String disputeId;

    @Column(name = "match_status", nullable = false, length = 30)
    private String matchStatus = "PENDING";

    @Column(name = "match_message", length = 500)
    private String matchMessage;

    @Column(name = "local_order_id")
    private Long localOrderId;

    @Column(name = "local_refund_id")
    private Long localRefundId;

    @Column(name = "local_dispute_id")
    private Long localDisputeId;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @PrePersist
    @PreUpdate
    void updateTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        lastSyncedAt = now;
    }

    public Long getId() { return id; }
    public RazorpaySettlement getSettlement() { return settlement; }
    public void setSettlement(RazorpaySettlement value) { settlement = value; }
    public String getProviderEntityId() { return providerEntityId; }
    public void setProviderEntityId(String value) { providerEntityId = value; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String value) { entryType = value; }
    public long getDebitPaise() { return debitPaise; }
    public void setDebitPaise(long value) { debitPaise = value; }
    public long getCreditPaise() { return creditPaise; }
    public void setCreditPaise(long value) { creditPaise = value; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long value) { amountPaise = value; }
    public long getFeePaise() { return feePaise; }
    public void setFeePaise(long value) { feePaise = value; }
    public long getTaxPaise() { return taxPaise; }
    public void setTaxPaise(long value) { taxPaise = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public boolean isOnHold() { return onHold; }
    public void setOnHold(boolean value) { onHold = value; }
    public boolean isSettled() { return settled; }
    public void setSettled(boolean value) { settled = value; }
    public LocalDateTime getProviderCreatedAt() { return providerCreatedAt; }
    public void setProviderCreatedAt(LocalDateTime value) { providerCreatedAt = value; }
    public LocalDateTime getProviderSettledAt() { return providerSettledAt; }
    public void setProviderSettledAt(LocalDateTime value) { providerSettledAt = value; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String value) { paymentId = value; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String value) { orderId = value; }
    public String getSettlementUtr() { return settlementUtr; }
    public void setSettlementUtr(String value) { settlementUtr = value; }
    public String getMethod() { return method; }
    public void setMethod(String value) { method = value; }
    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String value) { disputeId = value; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String value) { matchStatus = value; }
    public String getMatchMessage() { return matchMessage; }
    public void setMatchMessage(String value) { matchMessage = value; }
    public Long getLocalOrderId() { return localOrderId; }
    public void setLocalOrderId(Long value) { localOrderId = value; }
    public Long getLocalRefundId() { return localRefundId; }
    public void setLocalRefundId(Long value) { localRefundId = value; }
    public Long getLocalDisputeId() { return localDisputeId; }
    public void setLocalDisputeId(Long value) { localDisputeId = value; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String value) { rawPayload = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
}

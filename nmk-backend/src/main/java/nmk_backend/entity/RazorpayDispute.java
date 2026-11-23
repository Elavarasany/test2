package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "razorpay_disputes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_razorpay_dispute_provider_id",
                columnNames = "provider_dispute_id"
        ),
        indexes = {
                @Index(name = "idx_razorpay_dispute_status_deadline", columnList = "status, respond_by"),
                @Index(name = "idx_razorpay_dispute_payment", columnList = "provider_payment_id"),
                @Index(name = "idx_razorpay_dispute_match", columnList = "match_status, last_provider_event_at")
        }
)
public class RazorpayDispute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_dispute_id", nullable = false, length = 64)
    private String providerDisputeId;

    @Column(name = "provider_payment_id", nullable = false, length = 64)
    private String providerPaymentId;

    @Column(name = "local_order_id")
    private Long localOrderId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "amount_deducted_paise", nullable = false)
    private long amountDeductedPaise;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "reason_description", length = 500)
    private String reasonDescription;

    @Column(name = "respond_by")
    private LocalDateTime respondBy;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(length = 40)
    private String phase;

    @Column(name = "provider_created_at")
    private LocalDateTime providerCreatedAt;

    @Column(name = "last_provider_event_at")
    private LocalDateTime lastProviderEventAt;

    @Column(name = "last_provider_event_id", length = 150)
    private String lastProviderEventId;

    @Column(name = "last_provider_event_type", length = 100)
    private String lastProviderEventType;

    @Column(name = "match_status", nullable = false, length = 30)
    private String matchStatus = "PENDING";

    @Column(name = "match_message", length = 500)
    private String matchMessage;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @PrePersist
    @PreUpdate
    void updateSyncTime() {
        lastSyncedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getProviderDisputeId() { return providerDisputeId; }
    public void setProviderDisputeId(String value) { providerDisputeId = value; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String value) { providerPaymentId = value; }
    public Long getLocalOrderId() { return localOrderId; }
    public void setLocalOrderId(Long value) { localOrderId = value; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long value) { amountPaise = value; }
    public long getAmountDeductedPaise() { return amountDeductedPaise; }
    public void setAmountDeductedPaise(long value) { amountDeductedPaise = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public String getReasonDescription() { return reasonDescription; }
    public void setReasonDescription(String value) { reasonDescription = value; }
    public LocalDateTime getRespondBy() { return respondBy; }
    public void setRespondBy(LocalDateTime value) { respondBy = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getPhase() { return phase; }
    public void setPhase(String value) { phase = value; }
    public LocalDateTime getProviderCreatedAt() { return providerCreatedAt; }
    public void setProviderCreatedAt(LocalDateTime value) { providerCreatedAt = value; }
    public LocalDateTime getLastProviderEventAt() { return lastProviderEventAt; }
    public void setLastProviderEventAt(LocalDateTime value) { lastProviderEventAt = value; }
    public String getLastProviderEventId() { return lastProviderEventId; }
    public void setLastProviderEventId(String value) { lastProviderEventId = value; }
    public String getLastProviderEventType() { return lastProviderEventType; }
    public void setLastProviderEventType(String value) { lastProviderEventType = value; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String value) { matchStatus = value; }
    public String getMatchMessage() { return matchMessage; }
    public void setMatchMessage(String value) { matchMessage = value; }
    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
}

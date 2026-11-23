package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "order_refunds",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_refunds_razorpay_refund_id", columnNames = "razorpay_refund_id"),
                @UniqueConstraint(name = "uk_order_refunds_request_key", columnNames = "request_idempotency_key"),
                @UniqueConstraint(name = "uk_order_refunds_payment_receipt", columnNames = {"razorpay_payment_id", "receipt"})
        },
        indexes = {
                @Index(name = "idx_order_refunds_order_status", columnList = "order_id, status"),
                @Index(name = "idx_order_refunds_payment_id", columnList = "razorpay_payment_id")
        }
)
public class OrderRefund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "razorpay_refund_id", length = 64)
    private String razorpayRefundId;

    @Column(name = "request_idempotency_key", length = 80)
    private String requestIdempotencyKey;

    @Column(name = "receipt", length = 64)
    private String receipt;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "initiated_by", length = 100)
    private String initiatedBy;

    @Column(name = "razorpay_payment_id", nullable = false, length = 64)
    private String razorpayPaymentId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_provider_event_id", length = 150)
    private String lastProviderEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public void setOrder(Order value) { order = value; }
    public String getRazorpayRefundId() { return razorpayRefundId; }
    public void setRazorpayRefundId(String value) { razorpayRefundId = value; }
    public String getRequestIdempotencyKey() { return requestIdempotencyKey; }
    public void setRequestIdempotencyKey(String value) { requestIdempotencyKey = value; }
    public String getReceipt() { return receipt; }
    public void setReceipt(String value) { receipt = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(String value) { initiatedBy = value; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String value) { razorpayPaymentId = value; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long value) { amountPaise = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getLastProviderEventId() { return lastProviderEventId; }
    public void setLastProviderEventId(String value) { lastProviderEventId = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

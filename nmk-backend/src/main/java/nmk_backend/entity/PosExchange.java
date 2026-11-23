package nmk_backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "pos_exchanges",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pos_exchanges_return", columnNames = "return_id"),
                @UniqueConstraint(name = "uk_pos_exchanges_replacement_sale", columnNames = "replacement_sale_id"),
                @UniqueConstraint(name = "uk_pos_exchanges_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_pos_exchanges_original_created", columnList = "original_sale_id, created_at"),
                @Index(name = "idx_pos_exchanges_replacement_created", columnList = "replacement_sale_id, created_at")
        }
)
public class PosExchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_sale_id", nullable = false)
    private PosSale originalSale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private PosReturn returnRecord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "replacement_sale_id", nullable = false)
    private PosSale replacementSale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    @Column(name = "processed_by_username", nullable = false, length = 100)
    private String processedByUsername;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "returned_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal returnedValue;

    @Column(name = "replacement_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal replacementValue;

    @Column(name = "credit_applied", nullable = false, precision = 12, scale = 2)
    private BigDecimal creditApplied;

    @Column(name = "actual_refund", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualRefund = BigDecimal.ZERO;

    @Column(name = "amount_collected", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountCollected = BigDecimal.ZERO;

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public PosSale getOriginalSale() { return originalSale; }
    public void setOriginalSale(PosSale value) { originalSale = value; }
    public PosReturn getReturnRecord() { return returnRecord; }
    public void setReturnRecord(PosReturn value) { returnRecord = value; }
    public PosSale getReplacementSale() { return replacementSale; }
    public void setReplacementSale(PosSale value) { replacementSale = value; }
    public PosShift getShift() { return shift; }
    public void setShift(PosShift value) { shift = value; }
    public String getProcessedByUsername() { return processedByUsername; }
    public void setProcessedByUsername(String value) { processedByUsername = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public BigDecimal getReturnedValue() { return returnedValue; }
    public void setReturnedValue(BigDecimal value) { returnedValue = value; }
    public BigDecimal getReplacementValue() { return replacementValue; }
    public void setReplacementValue(BigDecimal value) { replacementValue = value; }
    public BigDecimal getCreditApplied() { return creditApplied; }
    public void setCreditApplied(BigDecimal value) { creditApplied = value; }
    public BigDecimal getActualRefund() { return actualRefund; }
    public void setActualRefund(BigDecimal value) { actualRefund = value; }
    public BigDecimal getAmountCollected() { return amountCollected; }
    public void setAmountCollected(BigDecimal value) { amountCollected = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

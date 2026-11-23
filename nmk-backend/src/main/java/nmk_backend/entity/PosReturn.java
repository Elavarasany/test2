package nmk_backend.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "pos_returns",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pos_returns_number", columnNames = "return_number"),
                @UniqueConstraint(name = "uk_pos_returns_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_pos_returns_sale_created", columnList = "sale_id, created_at"),
                @Index(name = "idx_pos_returns_status_created_sale", columnList = "status, created_at, sale_id")
        }
)
public class PosReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_number", nullable = false, length = 80)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private PosSale sale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    @Column(name = "processed_by_username", nullable = false, length = 100)
    private String processedByUsername;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "refund_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundTotal = BigDecimal.ZERO;

    @Column(name = "return_type", nullable = false, length = 20)
    private String returnType = "RETURN";

    @Column(nullable = false, length = 20)
    private String status = "COMPLETED";

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @BatchSize(size = 50)
    @OneToMany(mappedBy = "returnRecord", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PosReturnItem> items = new ArrayList<>();

    @BatchSize(size = 50)
    @OneToMany(mappedBy = "returnRecord", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PosRefundPayment> refundPayments = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (returnNumber == null || returnNumber.isBlank()) {
            returnNumber = "RET-" + java.time.LocalDate.now().toString().replace("-", "")
                    + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        }
    }

    public Long getId() { return id; }
    public String getReturnNumber() { return returnNumber; }
    public void setReturnNumber(String value) { this.returnNumber = value; }
    public PosSale getSale() { return sale; }
    public void setSale(PosSale value) { this.sale = value; }
    public PosShift getShift() { return shift; }
    public void setShift(PosShift value) { this.shift = value; }
    public String getProcessedByUsername() { return processedByUsername; }
    public void setProcessedByUsername(String value) { this.processedByUsername = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public BigDecimal getRefundTotal() { return refundTotal; }
    public void setRefundTotal(BigDecimal value) { this.refundTotal = value; }
    public String getReturnType() { return returnType; }
    public void setReturnType(String value) { returnType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { this.requestFingerprint = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<PosReturnItem> getItems() { return items; }
    public List<PosRefundPayment> getRefundPayments() { return refundPayments; }
}
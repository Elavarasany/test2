package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "supplier_returns",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supplier_return_number", columnNames = "return_number"),
                @UniqueConstraint(name = "uk_supplier_return_idempotency", columnNames = "idempotency_key")
        },
        indexes = @Index(name = "idx_supplier_returns_po_created", columnList = "purchase_order_id, created_at"))
public class SupplierReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_number", nullable = false, length = 80)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "processed_by_username", nullable = false, length = 100)
    private String processedByUsername;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "supplierReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierReturnItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (returnNumber == null || returnNumber.isBlank()) {
            returnNumber = "SUP-RET-" + LocalDate.now().toString().replace("-", "")
                    + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        }
    }

    public Long getId() { return id; }
    public String getReturnNumber() { return returnNumber; }
    public PurchaseOrder getPurchaseOrder() { return purchaseOrder; }
    public void setPurchaseOrder(PurchaseOrder value) { purchaseOrder = value; }
    public String getProcessedByUsername() { return processedByUsername; }
    public void setProcessedByUsername(String value) { processedByUsername = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<SupplierReturnItem> getItems() { return items; }
}
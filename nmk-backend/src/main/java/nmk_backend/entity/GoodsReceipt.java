package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "goods_receipts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_goods_receipt_number", columnNames = "receipt_number"),
                @UniqueConstraint(name = "uk_goods_receipt_idempotency", columnNames = "idempotency_key")
        },
        indexes = @Index(name = "idx_goods_receipts_po_created", columnList = "purchase_order_id, created_at"))
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_number", nullable = false, length = 80)
    private String receiptNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "received_by_username", nullable = false, length = 100)
    private String receivedByUsername;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GoodsReceiptItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (receiptNumber == null || receiptNumber.isBlank()) {
            receiptNumber = "GR-" + LocalDate.now().toString().replace("-", "")
                    + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        }
    }

    public Long getId() { return id; }
    public String getReceiptNumber() { return receiptNumber; }
    public PurchaseOrder getPurchaseOrder() { return purchaseOrder; }
    public void setPurchaseOrder(PurchaseOrder value) { purchaseOrder = value; }
    public String getReceivedByUsername() { return receivedByUsername; }
    public void setReceivedByUsername(String value) { receivedByUsername = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<GoodsReceiptItem> getItems() { return items; }
}
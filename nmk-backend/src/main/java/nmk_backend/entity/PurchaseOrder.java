package nmk_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase_orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_purchase_order_number", columnNames = "order_number"),
                @UniqueConstraint(name = "uk_purchase_order_idempotency", columnNames = "idempotency_key")
        },
        indexes = @Index(name = "idx_purchase_orders_location_status", columnList = "location_id, status"))
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 80)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "created_by_username", nullable = false, length = 100)
    private String createdByUsername;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (orderNumber == null || orderNumber.isBlank()) {
            orderNumber = "PO-" + LocalDate.now().toString().replace("-", "")
                    + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        }
    }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier value) { supplier = value; }
    public InventoryLocation getLocation() { return location; }
    public void setLocation(InventoryLocation value) { location = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getCreatedByUsername() { return createdByUsername; }
    public void setCreatedByUsername(String value) { createdByUsername = value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = value; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal value) { subtotal = value; }
    public LocalDateTime getOrderedAt() { return orderedAt; }
    public void setOrderedAt(LocalDateTime value) { orderedAt = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<PurchaseOrderItem> getItems() { return items; }
}
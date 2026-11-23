package nmk_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_bills",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supplier_bill_invoice", columnNames = {"supplier_id", "invoice_number"}),
                @UniqueConstraint(name = "uk_supplier_bill_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_supplier_bills_supplier_due", columnList = "supplier_id, due_date, status"),
                @Index(name = "idx_supplier_bills_po", columnList = "purchase_order_id")
        })
public class SupplierBill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @Column(name = "invoice_number", nullable = false, length = 100)
    private String invoiceNumber;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount = new BigDecimal("0.00");

    @Column(name = "credited_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal creditedAmount = new BigDecimal("0.00");

    @Column(name = "bill_type", nullable = false, length = 20)
    private String billType;

    @Column(nullable = false, length = 20)
    private String status = "POSTED";

    @Column(name = "created_by_username", nullable = false, length = 100)
    private String createdByUsername;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (paidAmount == null) paidAmount = new BigDecimal("0.00");
        if (creditedAmount == null) creditedAmount = new BigDecimal("0.00");
    }

    public Long getId() { return id; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier value) { supplier = value; }
    public PurchaseOrder getPurchaseOrder() { return purchaseOrder; }
    public void setPurchaseOrder(PurchaseOrder value) { purchaseOrder = value; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String value) { invoiceNumber = value; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate value) { invoiceDate = value; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate value) { dueDate = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal value) { paidAmount = value; }
    public BigDecimal getCreditedAmount() { return creditedAmount; }
    public void setCreditedAmount(BigDecimal value) { creditedAmount = value; }
    public String getBillType() { return billType; }
    public void setBillType(String value) { billType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getCreatedByUsername() { return createdByUsername; }
    public void setCreatedByUsername(String value) { createdByUsername = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

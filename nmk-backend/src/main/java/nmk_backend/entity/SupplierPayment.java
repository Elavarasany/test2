package nmk_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_payment_idempotency", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_supplier_payments_bill_paid", columnList = "supplier_bill_id, paid_at"),
                @Index(name = "idx_supplier_payments_supplier_paid", columnList = "supplier_id, paid_at")
        })
public class SupplierPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_bill_id", nullable = false)
    private SupplierBill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 160)
    private String reference;

    @Column(name = "shift_id")
    private Long shiftId;

    @Column(nullable = false, length = 20)
    private String status = "CONFIRMED";

    @Column(name = "created_by_username", nullable = false, length = 100)
    private String createdByUsername;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    @PrePersist
    void prePersist() { if (paidAt == null) paidAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public SupplierBill getBill() { return bill; }
    public void setBill(SupplierBill value) { bill = value; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier value) { supplier = value; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String value) { paymentMethod = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public String getReference() { return reference; }
    public void setReference(String value) { reference = value; }
    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long value) { shiftId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getCreatedByUsername() { return createdByUsername; }
    public void setCreatedByUsername(String value) { createdByUsername = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public LocalDateTime getPaidAt() { return paidAt; }
}

package nmk_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_bill_credit_notes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supplier_credit_note_number", columnNames = {"supplier_id", "credit_note_number"}),
                @UniqueConstraint(name = "uk_supplier_credit_note_idempotency", columnNames = "idempotency_key")
        },
        indexes = @Index(name = "idx_supplier_credit_notes_bill_date", columnList = "supplier_bill_id, issue_date"))
public class SupplierBillCreditNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_bill_id", nullable = false)
    private SupplierBill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_return_id")
    private SupplierReturn supplierReturn;

    @Column(name = "credit_note_number", nullable = false, length = 100)
    private String creditNoteNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status = "CONFIRMED";

    @Column(name = "created_by_username", nullable = false, length = 100)
    private String createdByUsername;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public SupplierBill getBill() { return bill; }
    public void setBill(SupplierBill value) { bill = value; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier value) { supplier = value; }
    public SupplierReturn getSupplierReturn() { return supplierReturn; }
    public void setSupplierReturn(SupplierReturn value) { supplierReturn = value; }
    public String getCreditNoteNumber() { return creditNoteNumber; }
    public void setCreditNoteNumber(String value) { creditNoteNumber = value; }
    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate value) { issueDate = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
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

package nmk_backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "pos_refund_payments",
        indexes = @Index(name = "idx_pos_refund_payments_return", columnList = "return_id")
)
public class PosRefundPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private PosReturn returnRecord;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "transaction_reference", length = 150)
    private String transactionReference;

    public Long getId() { return id; }
    public PosReturn getReturnRecord() { return returnRecord; }
    public void setReturnRecord(PosReturn value) { this.returnRecord = value; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String value) { this.paymentMethod = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { this.amount = value; }
    public String getTransactionReference() { return transactionReference; }
    public void setTransactionReference(String value) { this.transactionReference = value; }
}
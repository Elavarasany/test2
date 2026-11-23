package nmk_backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "pos_exchange_credit_settlements",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pos_exchange_credit_exchange", columnNames = "exchange_id"),
                @UniqueConstraint(name = "uk_pos_exchange_credit_replacement", columnNames = "replacement_sale_id")
        },
        indexes = @Index(name = "idx_pos_exchange_credit_replacement", columnList = "replacement_sale_id")
)
public class PosExchangeCreditSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id", nullable = false)
    private PosExchange exchange;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "replacement_sale_id", nullable = false)
    private PosSale replacementSale;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public PosExchange getExchange() { return exchange; }
    public void setExchange(PosExchange value) { exchange = value; }
    public PosSale getReplacementSale() { return replacementSale; }
    public void setReplacementSale(PosSale value) { replacementSale = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

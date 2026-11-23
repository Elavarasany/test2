package nmk_backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "pos_shifts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pos_shifts_open_idempotency",
                columnNames = "idempotency_key"
        ),
        indexes = {
                @Index(name = "idx_pos_shifts_register_status", columnList = "register_id, status"),
                @Index(name = "idx_pos_shifts_cashier_opened", columnList = "opened_by_username, opened_at")
        }
)
public class PosShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "register_id", nullable = false)
    private PosRegister register;

    @Column(name = "opened_by_username", nullable = false, length = 100)
    private String openedByUsername;

    @Column(name = "closed_by_username", length = 100)
    private String closedByUsername;

    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2)
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @Column(name = "expected_cash", precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "closing_cash_count", precision = 12, scale = 2)
    private BigDecimal closingCashCount;

    @Column(name = "cash_variance", precision = 12, scale = 2)
    private BigDecimal cashVariance;

    @Column(name = "closing_reason", length = 500)
    private String closingReason;

    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (openedAt == null) openedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public PosRegister getRegister() { return register; }
    public void setRegister(PosRegister register) { this.register = register; }
    public String getOpenedByUsername() { return openedByUsername; }
    public void setOpenedByUsername(String value) { this.openedByUsername = value; }
    public String getClosedByUsername() { return closedByUsername; }
    public void setClosedByUsername(String value) { this.closedByUsername = value; }
    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal value) { this.openingFloat = value; }
    public BigDecimal getExpectedCash() { return expectedCash; }
    public void setExpectedCash(BigDecimal value) { this.expectedCash = value; }
    public BigDecimal getClosingCashCount() { return closingCashCount; }
    public void setClosingCashCount(BigDecimal value) { this.closingCashCount = value; }
    public BigDecimal getCashVariance() { return cashVariance; }
    public void setCashVariance(BigDecimal value) { this.cashVariance = value; }
    public String getClosingReason() { return closingReason; }
    public void setClosingReason(String value) { this.closingReason = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime value) { this.closedAt = value; }
    public Long getVersion() { return version; }
}
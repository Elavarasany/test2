package nmk_backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "pos_cash_movements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pos_cash_movement_idempotency",
                columnNames = "idempotency_key"
        ),
        indexes = @Index(name = "idx_pos_cash_movements_shift_created", columnList = "shift_id, created_at")
)
public class PosCashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    @Column(name = "movement_type", nullable = false, length = 30)
    private String movementType;

    @Column(nullable = false, length = 3)
    private String direction;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public PosShift getShift() { return shift; }
    public void setShift(PosShift shift) { this.shift = shift; }
    public String getMovementType() { return movementType; }
    public void setMovementType(String value) { this.movementType = value; }
    public String getDirection() { return direction; }
    public void setDirection(String value) { this.direction = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { this.amount = value; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String value) { this.referenceType = value; }
    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long value) { this.referenceId = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String value) { this.actorUsername = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
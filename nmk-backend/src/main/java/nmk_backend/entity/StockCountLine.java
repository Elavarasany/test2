package nmk_backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "stock_count_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stock_count_session_variant",
                columnNames = {"session_id", "variant_id"}
        ),
        indexes = @Index(name = "idx_stock_count_lines_session", columnList = "session_id")
)
public class StockCountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private StockCountSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(name = "snapshot_on_hand", nullable = false)
    private Integer snapshotOnHand;

    @Column(name = "counted_quantity")
    private Integer countedQuantity;

    @Column(name = "counted_by_username", length = 100)
    private String countedByUsername;

    @Column(name = "counted_at")
    private LocalDateTime countedAt;

    public Long getId() { return id; }
    public StockCountSession getSession() { return session; }
    public void setSession(StockCountSession value) { this.session = value; }
    public ProductVariant getVariant() { return variant; }
    public void setVariant(ProductVariant value) { this.variant = value; }
    public Integer getSnapshotOnHand() { return snapshotOnHand; }
    public void setSnapshotOnHand(Integer value) { this.snapshotOnHand = value; }
    public Integer getCountedQuantity() { return countedQuantity; }
    public void setCountedQuantity(Integer value) { this.countedQuantity = value; }
    public String getCountedByUsername() { return countedByUsername; }
    public void setCountedByUsername(String value) { this.countedByUsername = value; }
    public LocalDateTime getCountedAt() { return countedAt; }
    public void setCountedAt(LocalDateTime value) { this.countedAt = value; }
}
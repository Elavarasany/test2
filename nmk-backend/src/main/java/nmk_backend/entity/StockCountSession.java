package nmk_backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "stock_count_sessions",
        indexes = {
                @Index(name = "idx_stock_count_location_status", columnList = "location_id, status")
        }
)
public class StockCountSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(nullable = false, length = 20)
    private String status = "COUNTING";

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "started_by_username", nullable = false, length = 100)
    private String startedByUsername;

    @Column(name = "submitted_by_username", length = 100)
    private String submittedByUsername;

    @Column(name = "posted_by_username", length = 100)
    private String postedByUsername;

    @Column(name = "cancelled_by_username", length = 100)
    private String cancelledByUsername;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockCountLine> lines = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public InventoryLocation getLocation() { return location; }
    public void setLocation(InventoryLocation value) { this.location = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public String getStartedByUsername() { return startedByUsername; }
    public void setStartedByUsername(String value) { this.startedByUsername = value; }
    public String getSubmittedByUsername() { return submittedByUsername; }
    public void setSubmittedByUsername(String value) { this.submittedByUsername = value; }
    public String getPostedByUsername() { return postedByUsername; }
    public void setPostedByUsername(String value) { this.postedByUsername = value; }
    public String getCancelledByUsername() { return cancelledByUsername; }
    public void setCancelledByUsername(String value) { this.cancelledByUsername = value; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime value) { this.submittedAt = value; }
    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime value) { this.postedAt = value; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime value) { this.cancelledAt = value; }
    public List<StockCountLine> getLines() { return lines; }
}
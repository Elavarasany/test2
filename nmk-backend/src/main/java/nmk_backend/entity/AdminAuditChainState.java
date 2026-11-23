package nmk_backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "admin_audit_chain_state")
public class AdminAuditChainState {
    @Id
    private Integer id;

    @Column(name = "last_event_id")
    private Long lastEventId;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    public Integer getId() { return id; }
    public void setId(Integer value) { id = value; }
    public Long getLastEventId() { return lastEventId; }
    public void setLastEventId(Long value) { lastEventId = value; }
    public String getLastHash() { return lastHash; }
    public void setLastHash(String value) { lastHash = value; }
}

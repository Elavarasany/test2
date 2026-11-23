package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "admin_audit_events", indexes = {
        @Index(name = "idx_admin_audit_created", columnList = "created_at, id"),
        @Index(name = "idx_admin_audit_actor", columnList = "actor_username, created_at"),
        @Index(name = "idx_admin_audit_path", columnList = "request_path, created_at")
})
public class AdminAuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_username", length = 100)
    private String actorUsername;

    @Column(name = "actor_authorities", length = 300)
    private String actorAuthorities;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 500)
    private String requestPath;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(nullable = false, length = 12)
    private String outcome;

    @Column(name = "remote_address", length = 64)
    private String remoteAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "error_type", length = 160)
    private String errorType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "previous_hash", nullable = false, length = 64, updatable = false)
    private String previousHash;

    @Column(name = "event_hash", nullable = false, length = 64, updatable = false)
    private String eventHash;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String value) { actorUsername = value; }
    public String getActorAuthorities() { return actorAuthorities; }
    public void setActorAuthorities(String value) { actorAuthorities = value; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String value) { httpMethod = value; }
    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String value) { requestPath = value; }
    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int value) { responseStatus = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    public String getRemoteAddress() { return remoteAddress; }
    public void setRemoteAddress(String value) { remoteAddress = value; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String value) { userAgent = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String value) { errorType = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String value) { previousHash = value; }
    public String getEventHash() { return eventHash; }
    public void setEventHash(String value) { eventHash = value; }
}
package nmk_backend.dto;

import nmk_backend.entity.AdminAuditEvent;
import java.time.LocalDateTime;

public record AdminAuditEventResponse(
        Long id,
        String actorUsername,
        String actorAuthorities,
        String httpMethod,
        String requestPath,
        int responseStatus,
        String outcome,
        String remoteAddress,
        String userAgent,
        String requestId,
        String errorType,
        LocalDateTime createdAt
) {
    public static AdminAuditEventResponse from(AdminAuditEvent event) {
        return new AdminAuditEventResponse(
                event.getId(), event.getActorUsername(), event.getActorAuthorities(),
                event.getHttpMethod(), event.getRequestPath(), event.getResponseStatus(),
                event.getOutcome(), event.getRemoteAddress(), event.getUserAgent(),
                event.getRequestId(), event.getErrorType(), event.getCreatedAt()
        );
    }
}
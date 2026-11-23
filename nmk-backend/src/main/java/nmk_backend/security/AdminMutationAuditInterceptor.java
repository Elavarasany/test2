package nmk_backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.service.AdminAuditEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AdminMutationAuditInterceptor implements HandlerInterceptor {
    public static final String AUDIT_RECORDED_ATTRIBUTE = AdminMutationAuditInterceptor.class.getName() + ".RECORDED";
    private static final Logger log = LoggerFactory.getLogger(AdminMutationAuditInterceptor.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private final AdminAuditEventService auditService;

    public AdminMutationAuditInterceptor(AdminAuditEventService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        String path = request.getRequestURI();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (path == null || !path.startsWith("/api/admin/")
                || !isMutation(method)) return;
        request.setAttribute(AUDIT_RECORDED_ATTRIBUTE, Boolean.TRUE);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AdminAuditEvent event = new AdminAuditEvent();
        if (authentication != null && authentication.isAuthenticated()) {
            event.setActorUsername(limit(authentication.getName(), 100));
            event.setActorAuthorities(authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).sorted()
                    .collect(Collectors.joining(",")));
        }
        event.setHttpMethod(method);
        event.setRequestPath(limit(path, 500));
        event.setResponseStatus(response.getStatus());
        event.setOutcome(exception == null && response.getStatus() < 400 ? "SUCCESS" : "FAILURE");
        event.setRemoteAddress(limit(request.getRemoteAddr(), 64));
        event.setUserAgent(limit(request.getHeader("User-Agent"), 300));
        String requestId = request.getHeader("X-Request-ID");
        event.setRequestId(requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()
                ? requestId : null);
        event.setErrorType(exception == null ? null : limit(exception.getClass().getName(), 160));

        try {
            auditService.record(event);
        } catch (RuntimeException auditFailure) {
            // Do not replace the business response with an audit persistence error.
            log.error("Failed to persist admin mutation audit event for {} {}", method, path, auditFailure);
        }
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
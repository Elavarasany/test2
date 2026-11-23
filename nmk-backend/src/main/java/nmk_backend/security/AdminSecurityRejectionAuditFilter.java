package nmk_backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AdminSecurityRejectionAuditFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityRejectionAuditFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private final AdminAuditEventService auditService;

    public AdminSecurityRejectionAuditFilter(AdminAuditEventService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (path == null || !path.startsWith("/api/admin/") || !isMutation(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        filterChain.doFilter(request, response);

        int status = response.getStatus();
        if ((status != HttpServletResponse.SC_UNAUTHORIZED && status != HttpServletResponse.SC_FORBIDDEN)
                || Boolean.TRUE.equals(request.getAttribute(AdminMutationAuditInterceptor.AUDIT_RECORDED_ATTRIBUTE))) {
            return;
        }

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
        event.setResponseStatus(status);
        event.setOutcome("FAILURE");
        event.setErrorType(status == HttpServletResponse.SC_UNAUTHORIZED
                ? "SECURITY_AUTHENTICATION_REJECTED" : "SECURITY_ACCESS_DENIED");
        event.setRemoteAddress(limit(request.getRemoteAddr(), 64));
        event.setUserAgent(limit(request.getHeader("User-Agent"), 300));
        String requestId = request.getHeader("X-Request-ID");
        event.setRequestId(requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()
                ? requestId : null);

        try {
            auditService.record(event);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to persist security rejection audit event for {} {}", method, path, auditFailure);
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

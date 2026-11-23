package nmk_backend.security;

import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.service.AdminAuditEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminSecurityRejectionAuditFilterTest {
    @Mock private AdminAuditEventService auditService;

    @Test
    void recordsOnlyRejectedAdminMutationsNotAlreadyAuditedByMvc() throws Exception {
        AdminSecurityRejectionAuditFilter filter = new AdminSecurityRejectionAuditFilter(auditService);
        MockHttpServletRequest denied = new MockHttpServletRequest();
        denied.setRequestURI("/api/admin/staff/10/locations");
        denied.setMethod("PUT");
        denied.addHeader("X-Request-ID", "deny-123");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        FilterChain forbiddenChain = (request, response) ->
                ((MockHttpServletResponse) response).setStatus(403);

        filter.doFilter(denied, deniedResponse, forbiddenChain);

        ArgumentCaptor<AdminAuditEvent> captor = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).record(captor.capture());
        assertEquals("PUT", captor.getValue().getHttpMethod());
        assertEquals("/api/admin/staff/10/locations", captor.getValue().getRequestPath());
        assertEquals(403, captor.getValue().getResponseStatus());
        assertEquals("FAILURE", captor.getValue().getOutcome());
        assertEquals("SECURITY_ACCESS_DENIED", captor.getValue().getErrorType());
        assertEquals("deny-123", captor.getValue().getRequestId());

        reset(auditService);
        MockHttpServletRequest alreadyAudited = new MockHttpServletRequest();
        alreadyAudited.setRequestURI("/api/admin/pos/checkout");
        alreadyAudited.setMethod("POST");
        alreadyAudited.setAttribute(AdminMutationAuditInterceptor.AUDIT_RECORDED_ATTRIBUTE, Boolean.TRUE);
        filter.doFilter(alreadyAudited, new MockHttpServletResponse(), forbiddenChain);
        verifyNoInteractions(auditService);

        MockHttpServletRequest read = new MockHttpServletRequest();
        read.setRequestURI("/api/admin/audit/events");
        read.setMethod("GET");
        filter.doFilter(read, new MockHttpServletResponse(), forbiddenChain);
        verifyNoInteractions(auditService);
    }

    @Test
    void doesNotAuditSuccessfulAdminMutationsAtTheSecurityLayer() throws Exception {
        AdminSecurityRejectionAuditFilter filter = new AdminSecurityRejectionAuditFilter(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin/staff");
        request.setMethod("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((MockHttpServletResponse) res).setStatus(201));
        verifyNoInteractions(auditService);
    }
}

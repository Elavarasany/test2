package nmk_backend.security;

import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.service.AdminAuditEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminMutationAuditInterceptorTest {
    @Mock
    private AdminAuditEventService auditService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsAdminMutationWithoutCapturingRequestBody() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("cashier", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_CASHIER")))
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin/pos/checkout");
        request.setMethod("POST");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "NMK-POS-Test");
        request.addHeader("X-Request-ID", "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);

        new AdminMutationAuditInterceptor(auditService)
                .afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<AdminAuditEvent> captor = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).record(captor.capture());
        AdminAuditEvent event = captor.getValue();
        assertEquals("cashier", event.getActorUsername());
        assertEquals("ROLE_CASHIER", event.getActorAuthorities());
        assertEquals("POST", event.getHttpMethod());
        assertEquals("/api/admin/pos/checkout", event.getRequestPath());
        assertEquals(201, event.getResponseStatus());
        assertEquals("SUCCESS", event.getOutcome());
        assertEquals("req-123", event.getRequestId());
        assertEquals("NMK-POS-Test", event.getUserAgent());
    }

    @Test
    void recordsFailedMutationsButIgnoresReadsAndNonAdminPaths() throws Exception {
        MockHttpServletRequest failed = new MockHttpServletRequest();
        failed.setRequestURI("/api/admin/pos/shifts/open");
        failed.setMethod("POST");
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        failedResponse.setStatus(400);

        AdminMutationAuditInterceptor interceptor = new AdminMutationAuditInterceptor(auditService);
        interceptor.afterCompletion(failed, failedResponse, new Object(), null);

        MockHttpServletRequest read = new MockHttpServletRequest();
        read.setRequestURI("/api/admin/pos/sales");
        read.setMethod("GET");
        interceptor.afterCompletion(read, new MockHttpServletResponse(), new Object(), null);

        MockHttpServletRequest publicMutation = new MockHttpServletRequest();
        publicMutation.setRequestURI("/api/orders/checkout");
        publicMutation.setMethod("POST");
        interceptor.afterCompletion(publicMutation, new MockHttpServletResponse(), new Object(), null);

        ArgumentCaptor<AdminAuditEvent> captor = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService, times(1)).record(captor.capture());
        assertEquals("FAILURE", captor.getValue().getOutcome());
        assertEquals(400, captor.getValue().getResponseStatus());
    }

    @Test
    void rejectsUntrustedRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin/pos/shifts/open");
        request.setMethod("POST");
        request.addHeader("X-Request-ID", "bad request id");
        new AdminMutationAuditInterceptor(auditService)
                .afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        ArgumentCaptor<AdminAuditEvent> captor = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).record(captor.capture());
        assertNull(captor.getValue().getRequestId());
    }
}
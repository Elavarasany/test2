package nmk_backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import nmk_backend.dto.StalePaymentProviderReviewResponse;
import nmk_backend.service.AdminAuditEventService;
import nmk_backend.service.StalePaymentCreationReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StalePaymentCreationReviewControllerTest {

    @Mock
    private StalePaymentCreationReviewService reviewService;

    @Mock
    private AdminAuditEventService auditService;

    @Mock
    private HttpServletRequest request;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsSuccessfulProviderLookupWithoutSensitivePayload() {
        when(request.getRequestURI()).thenReturn("/api/admin/pos/payments/stale-creation-review/42/provider-status");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("POS test");
        when(request.getHeader("X-Request-ID")).thenReturn("req-42");
        when(reviewService.getProviderStatus(42L)).thenReturn(
                new StalePaymentProviderReviewResponse(
                        42L, "order_Abc123", "PENDING", "IN_PROGRESS", true,
                        LocalDateTime.now().minusMinutes(5), "attempted", 12500, 0, 12500,
                        "INR", 1, true, List.of(), "Read-only", LocalDateTime.now()
                )
        );

        var controller = new StalePaymentCreationReviewController(reviewService, auditService);
        ResponseEntity<?> response = controller.getProviderStatus(42L, request);

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<nmk_backend.entity.AdminAuditEvent> captor =
                ArgumentCaptor.forClass(nmk_backend.entity.AdminAuditEvent.class);
        verify(auditService).record(captor.capture());
        var event = captor.getValue();
        assertEquals("GET", event.getHttpMethod());
        assertEquals(200, event.getResponseStatus());
        assertEquals("SUCCESS", event.getOutcome());
        assertEquals("req-42", event.getRequestId());
        assertEquals("/api/admin/pos/payments/stale-creation-review/42/provider-status", event.getRequestPath());
        assertNull(event.getErrorType());
    }

    @Test
    void recordsProviderLookupFailureWithoutChangingBusinessResponse() {
        when(request.getRequestURI()).thenReturn("/api/admin/pos/payments/stale-creation-review/42/provider-status");
        when(reviewService.getProviderStatus(42L))
                .thenThrow(new StalePaymentCreationReviewService.ProviderStatusUnavailableException());

        var controller = new StalePaymentCreationReviewController(reviewService, auditService);
        ResponseEntity<?> response = controller.getProviderStatus(42L, request);

        assertEquals(502, response.getStatusCode().value());
        ArgumentCaptor<nmk_backend.entity.AdminAuditEvent> captor =
                ArgumentCaptor.forClass(nmk_backend.entity.AdminAuditEvent.class);
        verify(auditService).record(captor.capture());
        assertEquals("FAILURE", captor.getValue().getOutcome());
        assertEquals(502, captor.getValue().getResponseStatus());
        assertNotNull(captor.getValue().getErrorType());
    }
}

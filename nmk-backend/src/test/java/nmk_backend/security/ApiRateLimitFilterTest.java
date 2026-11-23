package nmk_backend.security;

import nmk_backend.service.ApiRateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiRateLimitFilterTest {
    @Mock private ApiRateLimitService service;

    private ApiRateLimitFilter filter() {
        return new ApiRateLimitFilter(service, 5, 5, 10, 15, 30, 60);
    }

    @Test
    void returns429ForExcessiveAdminLoginAttempts() throws Exception {
        when(service.isEnabled()).thenReturn(true);
        when(service.increment("admin-login", "192.0.2.4", 5, 60))
                .thenReturn(new ApiRateLimitService.RateLimitDecision(false, 22, 6, 5));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/admin/auth/login");
        request.setRemoteAddr("192.0.2.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);

        filter().doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("22", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("Too many requests"));
        assertFalse(chainCalled.get());
    }

    @Test
    void appliesDedicatedLimitToPrescriptionUploads() throws Exception {
        when(service.isEnabled()).thenReturn(true);
        when(service.increment("prescription-upload", "192.0.2.9", 6, 60))
                .thenReturn(new ApiRateLimitService.RateLimitDecision(false, 18, 7, 6));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/prescriptions/upload");
        request.setRemoteAddr("192.0.2.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(429, response.getStatus());
        assertEquals("18", response.getHeader("Retry-After"));
        assertFalse(chainCalled.get());
    }

    @Test
    void appliesDedicatedLimitToOnlineRefundInitiation() throws Exception {
        when(service.isEnabled()).thenReturn(true);
        when(service.increment("razorpay-refund-initiation", "192.0.2.10", 5, 60))
                .thenReturn(new ApiRateLimitService.RateLimitDecision(false, 30, 6, 5));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/admin/orders/45/refunds");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(429, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertFalse(chainCalled.get());
    }

    @Test
    void appliesDedicatedLimitToSettlementSynchronization() throws Exception {
        when(service.isEnabled()).thenReturn(true);
        when(service.increment("razorpay-settlement-sync", "192.0.2.10", 3, 60))
                .thenReturn(new ApiRateLimitService.RateLimitDecision(false, 45, 4, 3));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/admin/pos/payments/settlements/sync");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(429, response.getStatus());
        assertEquals("45", response.getHeader("Retry-After"));
        assertFalse(chainCalled.get());
    }

    @Test
    void providerWebhooksAreNeverRateLimited() throws Exception {
        when(service.isEnabled()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/payment/webhook");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
        verify(service).isEnabled();
        verifyNoMoreInteractions(service);
    }

    @Test
    void disabledLimiterPassesRequestsWithoutDatabaseAccess() throws Exception {
        when(service.isEnabled()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/orders/checkout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
        verify(service).isEnabled();
        verifyNoMoreInteractions(service);
    }

    @Test
    void productionProfileEnablesRateLimitingByDefault() throws Exception {
        var properties = org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(
                new org.springframework.core.io.ClassPathResource("application-prod.properties"));
        assertEquals("${APP_RATE_LIMIT_ENABLED:true}", properties.getProperty("app.rate-limit.enabled"));
    }
}

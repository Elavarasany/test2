package nmk_backend.service;

import nmk_backend.dto.CreateRazorpayRefundRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayRefundInitiationServiceTest {
    @Mock private RazorpayService razorpayService;
    @Mock private RazorpayRefundRequestService requestService;
    @Mock private RazorpayRefundGateway gateway;
    @Mock private RazorpayRefundReconciliationService reconciliationService;

    private final CreateRazorpayRefundRequest request =
            new CreateRazorpayRefundRequest(5000L, "Customer cancellation", "refund-key-123456");

    private RazorpayRefundRequestService.PreparedRefund prepared(String providerId) {
        return new RazorpayRefundRequestService.PreparedRefund(
                9L, 45L, "pay_123", 5000L, "INR", "PENDING",
                "nmk_receipt_123", "Customer cancellation",
                "refund-key-123456", providerId, true
        );
    }

    private RazorpayRefundInitiationService service() {
        return new RazorpayRefundInitiationService(
                razorpayService, requestService, gateway, reconciliationService);
    }

    @Test
    void submitsNormalRefundAndReconcilesProviderResponse() {
        when(requestService.prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin")).thenReturn(prepared(null));
        when(gateway.createNormalRefund("pay_123", 5000L, "nmk_receipt_123",
                "refund-key-123456", "Customer cancellation", 45L))
                .thenReturn(new RazorpayRefundProviderResponse(
                        "rfnd_123", "pay_123", 5000L, "INR", "processed", "nmk_receipt_123"));
        when(requestService.getByIdempotencyKey("refund-key-123456"))
                .thenReturn(new RazorpayRefundRequestService.PreparedRefund(
                        9L, 45L, "pay_123", 5000L, "INR", "PROCESSED",
                        "nmk_receipt_123", "Customer cancellation",
                        "refund-key-123456", "rfnd_123", false));

        var result = service().initiate(45L, request, "admin");

        assertEquals("PROCESSED", result.status());
        assertEquals("rfnd_123", result.razorpayRefundId());
        assertFalse(result.retryWithSameKey());
        verify(reconciliationService).reconcile(
                "api_refund-key-123456", "refund.processed", "rfnd_123",
                "pay_123", 5000L, "INR", "processed", "nmk_receipt_123");
    }

    @Test
    void providerConflictKeepsRequestPendingForSameKeyRetry() {
        when(requestService.prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin")).thenReturn(prepared(null));
        when(gateway.createNormalRefund(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyLong()))
                .thenThrow(new RazorpayRefundGatewayException(409));

        var result = service().initiate(45L, request, "admin");

        assertEquals("PENDING", result.status());
        assertTrue(result.retryWithSameKey());
        verify(reconciliationService, never()).markInitiationFailed(anyString());
    }

    @Test
    void definiteProviderRejectionReleasesReservedAmount() {
        when(requestService.prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin")).thenReturn(prepared(null));
        when(gateway.createNormalRefund(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyLong()))
                .thenThrow(new RazorpayRefundGatewayException(400));

        assertThrows(RazorpayRefundInitiationService.RefundProviderRejectedException.class,
                () -> service().initiate(45L, request, "admin"));

        verify(reconciliationService).markInitiationFailed("refund-key-123456");
    }

    @Test
    void retriesADeadlockedReservationBeforeCallingProvider() {
        when(requestService.prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin"))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(prepared("rfnd_123"));

        var result = service().initiate(45L, request, "admin");

        assertEquals("rfnd_123", result.razorpayRefundId());
        verify(requestService, times(2)).prepare(45L, 5000L,
                "Customer cancellation", "refund-key-123456", "admin");
        verifyNoInteractions(gateway);
    }

    @Test
    void idempotentReplayDoesNotCallProviderAgainWhenRefundIdIsKnown() {
        when(requestService.prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin")).thenReturn(prepared("rfnd_123"));

        var result = service().initiate(45L, request, "admin");

        assertEquals("rfnd_123", result.razorpayRefundId());
        verifyNoInteractions(gateway);
    }
}

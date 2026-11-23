package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayRefundRequestServiceTest {
    @Mock private OrderRepository orderRepository;
    @Mock private OrderRefundRepository refundRepository;

    private RazorpayRefundRequestService service() {
        return new RazorpayRefundRequestService(orderRepository, refundRepository);
    }

    private Order paidOrder() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 45L);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_123");
        order.setRazorpayOrderId("order_123");
        order.setRefundStatus("NONE");
        return order;
    }

    @Test
    void reservesRefundAmountBeforeProviderCall() {
        Order order = paidOrder();
        when(refundRepository.findByRequestIdempotencyKeyForUpdate("refund-key-123456"))
                .thenReturn(Optional.empty());
        when(orderRepository.findByIdForUpdate(45L)).thenReturn(Optional.of(order));
        when(refundRepository.sumActiveAmountPaiseByOrderId(45L)).thenReturn(2500L);
        when(refundRepository.saveAndFlush(any(OrderRefund.class)))
                .thenAnswer(invocation -> {
                    OrderRefund refund = invocation.getArgument(0);
                    ReflectionTestUtils.setField(refund, "id", 99L);
                    return refund;
                });

        var prepared = service().prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin");

        assertEquals(99L, prepared.localRefundId());
        assertEquals("PENDING", prepared.status());
        assertEquals("pay_123", prepared.paymentId());
        assertEquals(5000L, prepared.amountPaise());
        assertNotNull(prepared.receipt());
        assertEquals("PENDING", order.getRefundStatus());
        verify(orderRepository).save(order);
        verify(refundRepository).saveAndFlush(any(OrderRefund.class));
    }

    @Test
    void preventsRefundAboveRemainingCapturedAmount() {
        Order order = paidOrder();
        when(refundRepository.findByRequestIdempotencyKeyForUpdate("refund-key-123456"))
                .thenReturn(Optional.empty());
        when(orderRepository.findByIdForUpdate(45L)).thenReturn(Optional.of(order));
        when(refundRepository.sumActiveAmountPaiseByOrderId(45L)).thenReturn(9500L);

        assertThrows(IllegalArgumentException.class, () ->
                service().prepare(45L, 1000L, "Customer cancellation",
                        "refund-key-123456", "admin"));

        verify(refundRepository, never()).saveAndFlush(any());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void sameIdempotencyKeyReusesTheOriginalRequest() {
        Order order = paidOrder();
        OrderRefund existing = new OrderRefund();
        existing.setOrder(order);
        existing.setAmountPaise(5000L);
        existing.setCurrency("INR");
        existing.setStatus("PENDING");
        existing.setReason("Customer cancellation");
        existing.setRequestIdempotencyKey("refund-key-123456");
        existing.setReceipt("nmk_receipt_123");
        existing.setRazorpayPaymentId("pay_123");
        when(orderRepository.findByIdForUpdate(45L)).thenReturn(Optional.of(order));
        when(refundRepository.findByRequestIdempotencyKeyForUpdate("refund-key-123456"))
                .thenReturn(Optional.of(existing));

        var prepared = service().prepare(45L, 5000L, "Customer cancellation",
                "refund-key-123456", "admin");

        assertEquals("nmk_receipt_123", prepared.receipt());
        assertFalse(prepared.newlyCreated());
        verify(orderRepository).findByIdForUpdate(45L);
        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void blocksANewRefundWhileAnotherRefundIsPending() {
        Order order = paidOrder();
        OrderRefund pending = new OrderRefund();
        pending.setOrder(order);
        pending.setStatus("PENDING");
        when(refundRepository.findByRequestIdempotencyKeyForUpdate("another-refund-key-123"))
                .thenReturn(Optional.empty());
        when(orderRepository.findByIdForUpdate(45L)).thenReturn(Optional.of(order));
        when(refundRepository.findByOrderIdOrderByCreatedAtDesc(45L))
                .thenReturn(java.util.List.of(pending));

        assertThrows(IllegalStateException.class, () ->
                service().prepare(45L, 1000L, "Second request",
                        "another-refund-key-123", "admin"));

        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentAmount() {
        Order order = paidOrder();
        OrderRefund existing = new OrderRefund();
        existing.setOrder(order);
        existing.setAmountPaise(5000L);
        existing.setStatus("PENDING");
        existing.setReason("Customer cancellation");
        when(orderRepository.findByIdForUpdate(45L)).thenReturn(Optional.of(order));
        when(refundRepository.findByRequestIdempotencyKeyForUpdate("refund-key-123456"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () ->
                service().prepare(45L, 6000L, "Customer cancellation",
                        "refund-key-123456", "admin"));
    }
}

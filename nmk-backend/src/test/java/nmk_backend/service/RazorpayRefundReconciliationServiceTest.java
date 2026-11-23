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
class RazorpayRefundReconciliationServiceTest {
    @Mock private OrderRepository orderRepository;
    @Mock private OrderRefundRepository refundRepository;

    private RazorpayRefundReconciliationService service() {
        return new RazorpayRefundReconciliationService(orderRepository, refundRepository);
    }

    private Order paidOrder() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 17L);
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRefundStatus("NONE");
        return order;
    }

    private void stubNewRefund(Order order, long active, long processed) {
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(refundRepository.findByRazorpayRefundIdForUpdate("rfnd_123"))
                .thenReturn(Optional.empty());
        when(refundRepository.saveAndFlush(any(OrderRefund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.sumActiveAmountPaiseByOrderId(17L)).thenReturn(active);
        when(refundRepository.sumProcessedAmountPaiseByOrderId(17L)).thenReturn(processed);
    }

    @Test
    void pendingRefundIsRecordedWithoutChangingCapturedPayment() {
        Order order = paidOrder();
        stubNewRefund(order, 5000L, 0L);

        service().reconcile("evt_1", "refund.created", "rfnd_123",
                "pay_123", 5000L, "INR", "pending");

        assertEquals("PAID", order.getPaymentStatus());
        assertEquals("PENDING", order.getRefundStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void processedPartialRefundIsReconciledWithoutBlockingOrderPayment() {
        Order order = paidOrder();
        stubNewRefund(order, 5000L, 5000L);

        service().reconcile("evt_2", "refund.processed", "rfnd_123",
                "pay_123", 5000L, "INR", "processed");

        assertEquals("PAID", order.getPaymentStatus());
        assertEquals("PARTIALLY_REFUNDED", order.getRefundStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void pendingRefundRemainderKeepsOrderBlockedFromShipment() {
        Order order = paidOrder();
        stubNewRefund(order, 10000L, 5000L);

        service().reconcile("evt_pending_remainder", "refund.processed", "rfnd_123",
                "pay_123", 5000L, "INR", "processed");

        assertEquals("PAID", order.getPaymentStatus());
        assertEquals("PENDING", order.getRefundStatus());
        assertEquals("PENDING", order.getOrderStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void fullRefundMarksPaymentAsRefunded() {
        Order order = paidOrder();
        stubNewRefund(order, 12500L, 12500L);

        service().reconcile("evt_3", "refund.processed", "rfnd_123",
                "pay_123", 12500L, "INR", "processed");

        assertEquals("REFUNDED", order.getPaymentStatus());
        assertEquals("REFUNDED", order.getRefundStatus());
        assertEquals("PENDING", order.getOrderStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void refundWebhookReceiptLinksThePrecreatedIdempotentRequest() {
        Order order = paidOrder();
        OrderRefund pending = new OrderRefund();
        pending.setOrder(order);
        pending.setRazorpayPaymentId("pay_123");
        pending.setAmountPaise(5000L);
        pending.setCurrency("INR");
        pending.setStatus("PENDING");
        pending.setReceipt("nmk_receipt_123");
        pending.setRequestIdempotencyKey("refund-key-123456");

        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(refundRepository.findByRazorpayRefundIdForUpdate("rfnd_123"))
                .thenReturn(Optional.empty());
        when(refundRepository.findByRazorpayPaymentIdAndReceiptForUpdate("pay_123", "nmk_receipt_123"))
                .thenReturn(Optional.of(pending));
        when(refundRepository.saveAndFlush(any(OrderRefund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.sumActiveAmountPaiseByOrderId(17L)).thenReturn(5000L);
        when(refundRepository.sumProcessedAmountPaiseByOrderId(17L)).thenReturn(5000L);

        service().reconcile("evt_receipt", "refund.processed", "rfnd_123",
                "pay_123", 5000L, "INR", "processed", "nmk_receipt_123");

        assertEquals("rfnd_123", pending.getRazorpayRefundId());
        assertEquals("PROCESSED", pending.getStatus());
        assertEquals("PARTIALLY_REFUNDED", order.getRefundStatus());
        verify(refundRepository, never()).findByRazorpayPaymentIdAndReceiptForUpdate("pay_123", "different_receipt");
    }

    @Test
    void apiReceiptMergesAWebhookRowCreatedWithoutReceipt() {
        Order order = paidOrder();
        OrderRefund providerRow = new OrderRefund();
        providerRow.setOrder(order);
        providerRow.setRazorpayRefundId("rfnd_123");
        providerRow.setRazorpayPaymentId("pay_123");
        providerRow.setAmountPaise(5000L);
        providerRow.setCurrency("INR");
        providerRow.setStatus("PROCESSED");
        providerRow.setLastProviderEventId("evt_without_receipt");

        OrderRefund localRequest = new OrderRefund();
        localRequest.setOrder(order);
        localRequest.setRazorpayPaymentId("pay_123");
        localRequest.setAmountPaise(5000L);
        localRequest.setCurrency("INR");
        localRequest.setStatus("PENDING");
        localRequest.setReceipt("nmk_receipt_123");
        localRequest.setRequestIdempotencyKey("refund-key-123456");

        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(refundRepository.findByRazorpayRefundIdForUpdate("rfnd_123"))
                .thenReturn(Optional.of(providerRow));
        when(refundRepository.findByRazorpayPaymentIdAndReceiptForUpdate("pay_123", "nmk_receipt_123"))
                .thenReturn(Optional.of(localRequest));
        when(refundRepository.saveAndFlush(any(OrderRefund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.sumActiveAmountPaiseByOrderId(17L)).thenReturn(5000L);
        when(refundRepository.sumProcessedAmountPaiseByOrderId(17L)).thenReturn(5000L);

        service().reconcile("api_refund-key-123456", "refund.processed",
                "rfnd_123", "pay_123", 5000L, "INR", "processed", "nmk_receipt_123");

        assertEquals("rfnd_123", localRequest.getRazorpayRefundId());
        assertEquals("PROCESSED", localRequest.getStatus());
        verify(refundRepository).delete(providerRow);
        verify(refundRepository).flush();
        verify(refundRepository).saveAndFlush(localRequest);
    }

    @Test
    void refundTotalCannotExceedCapturedOrderAmount() {
        Order order = paidOrder();
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(refundRepository.findByRazorpayRefundIdForUpdate("rfnd_123"))
                .thenReturn(Optional.empty());
        when(refundRepository.saveAndFlush(any(OrderRefund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.sumActiveAmountPaiseByOrderId(17L)).thenReturn(13000L);

        assertThrows(IllegalArgumentException.class, () ->
                service().reconcile("evt_4", "refund.processed", "rfnd_123",
                        "pay_123", 13000L, "INR", "processed"));

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void mismatchedProviderCurrencyIsRejected() {
        Order order = paidOrder();
        assertThrows(IllegalArgumentException.class, () ->
                service().reconcile("evt_5", "refund.processed", "rfnd_123",
                        "pay_123", 5000L, "USD", "processed"));

        verifyNoInteractions(refundRepository);
        verify(orderRepository, never()).save(any(Order.class));
    }
}

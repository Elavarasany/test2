package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.entity.RazorpayDispute;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayDisputeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayDisputeReconciliationServiceTest {
    @Mock private RazorpayDisputeRepository disputeRepository;
    @Mock private OrderRepository orderRepository;

    private RazorpayDisputeReconciliationService service() {
        return new RazorpayDisputeReconciliationService(disputeRepository, orderRepository);
    }

    private Order paidOrder() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 45L);
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_123");
        return order;
    }

    @Test
    void recordsDisputeAndMatchesItToLocalPaidOrderWithoutChangingOrder() {
        Order order = paidOrder();
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(disputeRepository.findByProviderDisputeIdForUpdate("disp_123"))
                .thenReturn(Optional.empty());
        when(disputeRepository.saveAndFlush(any(RazorpayDispute.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().reconcile(
                "evt_123", "payment.dispute.created", "disp_123", "pay_123",
                10000L, "INR", 0L, "goods_or_services_not_received",
                "Goods not received", 1800000000L, "open", "chargeback",
                1700000000L, 1700000100L);

        var captor = org.mockito.ArgumentCaptor.forClass(RazorpayDispute.class);
        verify(disputeRepository).saveAndFlush(captor.capture());
        RazorpayDispute saved = captor.getValue();
        assertEquals("OPEN", saved.getStatus());
        assertEquals("MATCHED", saved.getMatchStatus());
        assertEquals(45L, saved.getLocalOrderId());
        assertEquals(10000L, saved.getAmountPaise());
        assertEquals(0L, saved.getAmountDeductedPaise());
        assertEquals("chargeback", saved.getPhase());
        assertEquals("PAID", order.getPaymentStatus());
        assertEquals(new BigDecimal("1000.00"), order.getTotalAmount());
    }

    @Test
    void ignoresOlderWebhookEventInsteadOfDowngradingCurrentDisputeStatus() {
        RazorpayDispute existing = new RazorpayDispute();
        existing.setProviderDisputeId("disp_123");
        existing.setProviderPaymentId("pay_123");
        existing.setAmountPaise(10000L);
        existing.setAmountDeductedPaise(10000L);
        existing.setCurrency("INR");
        existing.setStatus("LOST");
        existing.setLastProviderEventAt(LocalDateTime.of(2026, 11, 22, 10, 0));
        existing.setLastProviderEventId("evt_new");
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(paidOrder()));
        when(disputeRepository.findByProviderDisputeIdForUpdate("disp_123"))
                .thenReturn(Optional.of(existing));

        service().reconcile(
                "evt_old", "payment.dispute.created", "disp_123", "pay_123",
                10000L, "INR", 0L, "goods_or_services_not_received",
                "Goods not received", null, "open", "chargeback",
                1700000000L, 1700000000L);

        verify(disputeRepository, never()).saveAndFlush(any());
        assertEquals("LOST", existing.getStatus());
        assertEquals("evt_new", existing.getLastProviderEventId());
    }

    @Test
    void marksDisputeAboveLocalOrderTotalAsMismatch() {
        Order order = paidOrder();
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(disputeRepository.findByProviderDisputeIdForUpdate("disp_123"))
                .thenReturn(Optional.empty());
        when(disputeRepository.saveAndFlush(any(RazorpayDispute.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().reconcile(
                "evt_123", "payment.dispute.created", "disp_123", "pay_123",
                150000L, "INR", 0L, "reason", "description",
                null, "open", "chargeback", 1700000000L, 1700000100L);

        var captor = org.mockito.ArgumentCaptor.forClass(RazorpayDispute.class);
        verify(disputeRepository).saveAndFlush(captor.capture());
        assertEquals("MISMATCH", captor.getValue().getMatchStatus());
    }
}

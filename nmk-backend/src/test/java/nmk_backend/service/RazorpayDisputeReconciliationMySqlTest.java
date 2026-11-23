package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayDisputeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class RazorpayDisputeReconciliationMySqlTest {
    @Autowired private RazorpayDisputeReconciliationService service;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RazorpayDisputeRepository disputeRepository;

    @Test
    void persistsDisputeAndIgnoresOlderWebhookStatusOnMysql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String paymentId = "pay_" + suffix;
        String disputeId = "disp_" + suffix;
        Order order = new Order();
        order.setCustomerName("Dispute ledger test");
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId(paymentId);
        orderRepository.saveAndFlush(order);

        long createdAt = Instant.now().getEpochSecond();
        long respondBy = createdAt + 86400;

        service.reconcile(
                "evt_created_" + suffix, "payment.dispute.created",
                disputeId, paymentId, 10000L, "INR", 0L,
                "goods_or_services_not_received", "Goods not received",
                respondBy, "open", "chargeback", createdAt, createdAt);

        service.reconcile(
                "evt_lost_" + suffix, "payment.dispute.lost",
                disputeId, paymentId, 10000L, "INR", 10000L,
                "goods_or_services_not_received", "Goods not received",
                respondBy, "lost", "chargeback", createdAt, createdAt + 20);

        service.reconcile(
                "evt_old_" + suffix, "payment.dispute.created",
                disputeId, paymentId, 10000L, "INR", 0L,
                "goods_or_services_not_received", "Goods not received",
                respondBy, "open", "chargeback", createdAt, createdAt);

        var saved = disputeRepository.findByProviderDisputeId(disputeId).orElseThrow();
        assertEquals("LOST", saved.getStatus());
        assertEquals(10000L, saved.getAmountDeductedPaise());
        assertEquals("MATCHED", saved.getMatchStatus());
        assertEquals(order.getId(), saved.getLocalOrderId());
    }
}

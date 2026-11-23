package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class RazorpayRefundReconciliationMySqlTest {
    @Autowired private RazorpayRefundReconciliationService service;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderRefundRepository refundRepository;

    @Test
    void partialRefundsAccumulateToFullRefundOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String paymentId = "pay_" + suffix;
        Order order = new Order();
        order.setCustomerName("Refund reconciliation test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId(paymentId);
        order = orderRepository.saveAndFlush(order);
        long refundsBefore = refundRepository.count();

        service.reconcile("evt_" + suffix + "a", "refund.processed",
                "rfnd_" + suffix + "a", paymentId, 5000L, "INR", "processed");

        Order partial = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("PAID", partial.getPaymentStatus());
        assertEquals("PARTIALLY_REFUNDED", partial.getRefundStatus());

        service.reconcile("evt_" + suffix + "b", "refund.processed",
                "rfnd_" + suffix + "b", paymentId, 7500L, "INR", "processed");

        Order fullyRefunded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("REFUNDED", fullyRefunded.getPaymentStatus());
        assertEquals("REFUNDED", fullyRefunded.getRefundStatus());
        assertEquals("PENDING", fullyRefunded.getOrderStatus());
        assertEquals(refundsBefore + 2, refundRepository.count());
    }

    @Test
    void webhookLinksExistingPendingRefundRequestByReceiptOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String paymentId = "pay_" + suffix;
        String refundId = "rfnd_" + suffix;
        String receipt = "nmk_" + suffix;
        String idempotencyKey = "refund_" + suffix;

        Order order = new Order();
        order.setCustomerName("Idempotent refund link test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId(paymentId);
        order = orderRepository.saveAndFlush(order);

        nmk_backend.entity.OrderRefund pending = new nmk_backend.entity.OrderRefund();
        pending.setOrder(order);
        pending.setRazorpayPaymentId(paymentId);
        pending.setAmountPaise(5000L);
        pending.setCurrency("INR");
        pending.setStatus("PENDING");
        pending.setReceipt(receipt);
        pending.setRequestIdempotencyKey(idempotencyKey);
        refundRepository.saveAndFlush(pending);
        long refundsBefore = refundRepository.count();

        service.reconcile("evt_" + suffix, "refund.processed",
                refundId, paymentId, 5000L, "INR", "processed", receipt);

        nmk_backend.entity.OrderRefund linked = refundRepository
                .findByRequestIdempotencyKey(idempotencyKey).orElseThrow();
        assertEquals(refundId, linked.getRazorpayRefundId());
        assertEquals("PROCESSED", linked.getStatus());
        assertEquals(refundsBefore, refundRepository.count());
    }

    @Test
    void apiReceiptMergesWebhookRowWithoutReceiptOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String paymentId = "pay_" + suffix;
        String refundId = "rfnd_" + suffix;
        String receipt = "nmk_" + suffix;
        String idempotencyKey = "refund_" + suffix;

        Order order = new Order();
        order.setCustomerName("Refund merge test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId(paymentId);
        order = orderRepository.saveAndFlush(order);

        nmk_backend.entity.OrderRefund providerRow = new nmk_backend.entity.OrderRefund();
        providerRow.setOrder(order);
        providerRow.setRazorpayRefundId(refundId);
        providerRow.setRazorpayPaymentId(paymentId);
        providerRow.setAmountPaise(5000L);
        providerRow.setCurrency("INR");
        providerRow.setStatus("PROCESSED");
        providerRow.setLastProviderEventId("evt_without_receipt");
        refundRepository.saveAndFlush(providerRow);

        nmk_backend.entity.OrderRefund localRequest = new nmk_backend.entity.OrderRefund();
        localRequest.setOrder(order);
        localRequest.setRazorpayPaymentId(paymentId);
        localRequest.setAmountPaise(5000L);
        localRequest.setCurrency("INR");
        localRequest.setStatus("PENDING");
        localRequest.setReceipt(receipt);
        localRequest.setRequestIdempotencyKey(idempotencyKey);
        localRequest.setReason("Customer cancellation");
        localRequest.setInitiatedBy("admin");
        refundRepository.saveAndFlush(localRequest);

        service.reconcile("api_" + suffix, "refund.processed",
                refundId, paymentId, 5000L, "INR", "processed", receipt);

        var linked = refundRepository.findByRequestIdempotencyKey(idempotencyKey).orElseThrow();
        assertEquals(refundId, linked.getRazorpayRefundId());
        assertEquals("PROCESSED", linked.getStatus());
        assertEquals(1, refundRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()).size());
    }

    @Test
    void overRefundTransactionRollsBackOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String paymentId = "pay_" + suffix;
        Order order = new Order();
        order.setCustomerName("Over-refund test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId(paymentId);
        order = orderRepository.saveAndFlush(order);
        long before = refundRepository.count();

        assertThrows(IllegalArgumentException.class, () ->
                service.reconcile("evt_" + suffix, "refund.processed",
                        "rfnd_" + suffix, paymentId, 13000L, "INR", "processed"));

        assertEquals(before, refundRepository.count());
        Order unchanged = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("PAID", unchanged.getPaymentStatus());
        assertEquals("NONE", unchanged.getRefundStatus());
    }
}

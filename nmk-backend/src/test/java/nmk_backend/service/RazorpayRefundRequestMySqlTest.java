package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class RazorpayRefundRequestMySqlTest {
    @Autowired private RazorpayRefundRequestService requestService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderRefundRepository refundRepository;

    @Test
    void concurrentRefundRequestsCannotReserveMoreThanOnePendingRefund() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = new Order();
        order.setCustomerName("Concurrent refund request test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_" + suffix);
        order.setRazorpayOrderId("order_" + suffix);
        order = orderRepository.saveAndFlush(order);
        Long orderId = order.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> attempt(orderId, suffix, "one", ready, start));
            Future<Boolean> second = executor.submit(() -> attempt(orderId, suffix, "two", ready, start));
            ready.await();
            start.countDown();

            int accepted = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, accepted);
            assertEquals(1, refundRepository.findByOrderIdOrderByCreatedAtDesc(orderId).size());
        }
    }

    private boolean attempt(Long orderId, String suffix, String label,
                            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                requestService.prepare(orderId, 7000L, "Concurrent partial refund",
                        "refund_" + suffix + "_" + label, "admin");
                return true;
            } catch (PessimisticLockingFailureException lockFailure) {
                if (attempt == 2) throw lockFailure;
                Thread.sleep(40L * (attempt + 1));
            } catch (IllegalStateException pendingRequest) {
                return false;
            }
        }
        return false;
    }

    @Test
    void persistsAndReusesTheSameRefundRequestOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = new Order();
        order.setCustomerName("Refund request idempotency test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_" + suffix);
        order.setRazorpayOrderId("order_" + suffix);
        order = orderRepository.saveAndFlush(order);

        String key = "refund_" + suffix;
        long before = refundRepository.count();

        var first = requestService.prepare(order.getId(), 5000L,
                "Customer cancellation", key, "admin");
        var retry = requestService.prepare(order.getId(), 5000L,
                "Customer cancellation", key, "admin");

        assertTrue(first.newlyCreated());
        assertFalse(retry.newlyCreated());
        assertEquals(first.localRefundId(), retry.localRefundId());
        assertEquals(first.receipt(), retry.receipt());
        assertEquals(before + 1, refundRepository.count());

        var history = requestService.listForOrder(order.getId());
        assertEquals(1, history.size());
        assertEquals(key, history.getFirst().requestIdempotencyKey());
        assertEquals("PENDING", history.getFirst().status());
    }
}


package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.exception.PaymentConfirmationConflictException;
import nmk_backend.repository.OrderRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class OrderMySqlConcurrencyTest {

    @Autowired
    private PaymentVerificationService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentSamePaymentIdIsConfirmedOnlyOnce() throws Exception {
        String razorpayOrderId = "ORDER-" + UUID.randomUUID();
        createPendingOrder(razorpayOrderId);

        List<Boolean> results = runConcurrently(
                () -> paymentService.confirmPayment(
                        razorpayOrderId, "pay_same"
                ).newlyConfirmed(),
                () -> paymentService.confirmPayment(
                        razorpayOrderId, "pay_same"
                ).newlyConfirmed()
        );

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, results.stream().filter(result -> !result).count());

        Order saved = orderRepository
                .findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow();

        assertEquals("PAID", saved.getPaymentStatus());
        assertEquals("pay_same", saved.getRazorpayPaymentId());
        assertEquals("CONFIRMED", saved.getOrderStatus());
        assertFalse(saved.isStockReserved());
        assertNotNull(saved.getTrackingToken());
    }

    @Test
    void concurrentDifferentPaymentIdsCannotBothBeRecorded()
            throws Exception {

        String razorpayOrderId = "ORDER-" + UUID.randomUUID();
        createPendingOrder(razorpayOrderId);

        List<Boolean> results = runConcurrently(
                () -> confirmOrConflict(razorpayOrderId, "pay_first"),
                () -> confirmOrConflict(razorpayOrderId, "pay_second")
        );

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, results.stream().filter(result -> !result).count());

        Order saved = orderRepository
                .findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow();

        assertEquals("PAID", saved.getPaymentStatus());
        assertTrue(
                "pay_first".equals(saved.getRazorpayPaymentId())
                        || "pay_second".equals(saved.getRazorpayPaymentId())
        );
        assertFalse(saved.isStockReserved());
    }

    private boolean confirmOrConflict(
            String razorpayOrderId,
            String paymentId
    ) {
        try {
            return paymentService.confirmPayment(
                    razorpayOrderId, paymentId
            ).newlyConfirmed();
        } catch (PaymentConfirmationConflictException exception) {
            return false;
        }
    }

    private void createPendingOrder(String razorpayOrderId) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            Order order = new Order();
            order.setTotalAmount(new BigDecimal("125.00"));
            order.setPaymentStatus("CREATED");
            order.setOrderStatus("PENDING");
            order.setStockReserved(true);
            order.setRazorpayOrderId(razorpayOrderId);

            orderRepository.saveAndFlush(order);
            return null;
        });
    }

    private List<Boolean> runConcurrently(
            Callable<Boolean> first,
            Callable<Boolean> second
    ) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> firstResult = executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Start signal timed out");
                }
                return first.call();
            });

            Future<Boolean> secondResult = executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Start signal timed out");
                }
                return second.call();
            });

            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();

            return List.of(
                    firstResult.get(30, TimeUnit.SECONDS),
                    secondResult.get(30, TimeUnit.SECONDS)
            );

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

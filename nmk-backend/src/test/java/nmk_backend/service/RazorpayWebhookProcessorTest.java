
package nmk_backend.service;

import tools.jackson.databind.ObjectMapper;

import nmk_backend.exception.PaymentConfirmationConflictException;
import nmk_backend.entity.Order;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayWebhookEventRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookProcessorTest {

    @Mock
    private RazorpayWebhookEventRepository eventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private PaymentVerificationService paymentVerificationService;

    @Mock
    private RazorpayRefundReconciliationService refundReconciliationService;

    @Mock
    private RazorpayDisputeReconciliationService disputeReconciliationService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private RazorpayWebhookProcessor processor;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(
                any(TransactionDefinition.class)
        )).thenReturn(transactionStatus);

        processor = new RazorpayWebhookProcessor(
                eventRepository,
                orderRepository,
                razorpayService,
                paymentVerificationService,
                objectMapper,
                refundReconciliationService,
                disputeReconciliationService,
                transactionManager
        );
    }

    private RazorpayWebhookEvent event(String payload) {

        RazorpayWebhookEvent event = new RazorpayWebhookEvent();

        event.setEventId("evt_test_1");
        event.setEventType("payment.captured");
        event.setRawPayload(payload);
        event.setStatus("RECEIVED");
        event.setNextAttemptAt(
                LocalDateTime.now().minusMinutes(1)
        );

        return event;
    }

    private String capturedPayload(int amount) {
        return """
                {
                  "event":"payment.captured",
                  "payload":{
                    "payment":{
                      "entity":{
                        "id":"pay_123",
                        "order_id":"order_123",
                        "amount":%d
                      }
                    }
                  }
                }
                """.formatted(amount);
    }

    private Order order() {
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("125.00"));
        return order;
    }

    @Test
    void disputeWebhookUpdatesDisputeLedgerWithoutChangingPaymentOrInventory() throws Exception {
        RazorpayWebhookEvent event = event("""
                {
                  "event":"payment.dispute.created",
                  "created_at":1700000100,
                  "payload":{
                    "payment":{"entity":{"id":"pay_123"}},
                    "dispute":{"entity":{
                      "id":"disp_123",
                      "payment_id":"pay_123",
                      "amount":10000,
                      "amount_deducted":0,
                      "currency":"INR",
                      "reason_code":"goods_or_services_not_received",
                      "reason_description":"Customer claims non-delivery",
                      "respond_by":1701000000,
                      "status":"open",
                      "phase":"chargeback",
                      "created_at":1700000000
                    }}
                  }
                }
                """);
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));

        processor.processOne(1L);

        assertEquals("PROCESSED", event.getStatus());
        verify(disputeReconciliationService).reconcile(
                "evt_test_1", "payment.dispute.created", "disp_123", "pay_123",
                10000L, "INR", 0L, "goods_or_services_not_received",
                "Customer claims non-delivery", 1701000000L, "open",
                "chargeback", 1700000000L, 1700000100L);
        verifyNoInteractions(paymentVerificationService, razorpayService, refundReconciliationService);
    }

    @Test
    void nonCaptureEventIsProcessedWithoutChangingOrder() throws Exception {

        RazorpayWebhookEvent event = event("""
                {"event":"payment.failed","payload":{}}
                """);

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        processor.processOne(1L);

        assertEquals("PROCESSED", event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());
        assertNotNull(event.getProcessedAt());

        verifyNoInteractions(
                orderRepository,
                razorpayService,
                paymentVerificationService
        );

        verify(eventRepository, times(2))
                .findByIdForUpdate(1L);

        verify(eventRepository, times(2))
                .save(event);
    }

    @Test
    void processedRefundWebhookUpdatesProviderRefundLedger()
            throws Exception {

        RazorpayWebhookEvent event = event("""
                {
                  "event":"refund.processed",
                  "payload":{
                    "refund":{
                      "entity":{
                        "id":"rfnd_123",
                        "payment_id":"pay_123",
                        "amount":5000,
                        "currency":"INR",
                        "status":"processed",
                        "receipt":"nmk_receipt_123"
                      }
                    }
                  }
                }
                """);
        event.setEventType("refund.processed");

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        processor.processOne(1L);

        assertEquals("PROCESSED", event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());

        verify(refundReconciliationService).reconcile(
                "evt_test_1", "refund.processed", "rfnd_123", "pay_123", 5000L, "INR", "processed", "nmk_receipt_123"
        );
        verifyNoInteractions(orderRepository, razorpayService, paymentVerificationService);
    }

    @Test
    void capturedPaymentIsVerifiedBeforeConfirmation() throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        Order order = order();

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123",
                "order_123",
                12500
        )).thenReturn(true);

        when(paymentVerificationService.confirmPayment(
                "order_123",
                "pay_123"
        )).thenReturn(
                new PaymentVerificationService.ConfirmationResult(
                        order,
                        true
                )
        );

        processor.processOne(1L);

        assertEquals("PROCESSED", event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());
        assertNotNull(event.getProcessedAt());

        InOrder orderOfOperations = inOrder(
                razorpayService,
                paymentVerificationService
        );

        orderOfOperations.verify(razorpayService)
                .isPaymentCapturedForOrder(
                        "pay_123",
                        "order_123",
                        12500
                );

        orderOfOperations.verify(paymentVerificationService)
                .confirmPayment("order_123", "pay_123");
    }

    @Test
    void paymentConfirmationConflictGoesDirectlyToManualReview()
            throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        Order order = order();

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123",
                "order_123",
                12500
        )).thenReturn(true);

        when(paymentVerificationService.confirmPayment(
                "order_123",
                "pay_123"
        )).thenThrow(
                new PaymentConfirmationConflictException(
                        "A different payment is already recorded"
                )
        );

        processor.processOne(1L);

        assertEquals("MANUAL_REVIEW", event.getStatus());
        assertEquals(1, event.getAttemptCount());

        assertTrue(
                event.getProcessingError().contains(
                        "A different payment is already recorded"
                )
        );

        assertNull(event.getNextAttemptAt());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());
        assertNull(event.getProcessedAt());

        verify(paymentVerificationService)
                .confirmPayment("order_123", "pay_123");
    }

    @Test
    void providerNotYetReportingCaptureSchedulesRetry() throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order()));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123",
                "order_123",
                12500
        )).thenReturn(false);

        LocalDateTime beforeProcessing = LocalDateTime.now();

        processor.processOne(1L);

        assertEquals("RETRY", event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getNextAttemptAt());
        assertTrue(
                event.getNextAttemptAt().isAfter(beforeProcessing)
        );
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());

        verifyNoInteractions(paymentVerificationService);
    }

    @Test
    void mismatchedAmountIsSentToManualReview() throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(999));

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order()));

        processor.processOne(1L);

        assertEquals("MANUAL_REVIEW", event.getStatus());
        assertTrue(
                event.getProcessingError().contains("amount")
        );
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());

        verifyNoInteractions(
                razorpayService,
                paymentVerificationService
        );
    }
    @Test
    void fractionalPaiseOrderAmountIsSentToManualReview()
            throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        Order order = order();
        order.setTotalAmount(new BigDecimal("125.001"));

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        processor.processOne(1L);

        assertEquals("MANUAL_REVIEW", event.getStatus());

        assertTrue(
                event.getProcessingError().toLowerCase().contains("amount")
        );

        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());

        verifyNoInteractions(
                razorpayService,
                paymentVerificationService
        );
    }

    @Test
    void eventIsNotClaimedWhenRetryIsNotDue() throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        event.setStatus("RETRY");
        event.setNextAttemptAt(
                LocalDateTime.now().plusMinutes(10)
        );

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        processor.processOne(1L);

        assertEquals("RETRY", event.getStatus());
        assertEquals(0, event.getAttemptCount());
        assertNull(event.getClaimToken());

        verify(eventRepository, never()).save(any());
        verifyNoInteractions(
                orderRepository,
                razorpayService,
                paymentVerificationService
        );
    }

    @Test
    void staleWorkerCannotFinalizeAfterAnotherWorkerTakesOwnership()
            throws Exception {

        RazorpayWebhookEvent event = event("""
                {"event":"payment.failed","payload":{}}
                """);

        AtomicInteger calls = new AtomicInteger();

        when(eventRepository.findByIdForUpdate(1L))
                .thenAnswer(invocation -> {

                    int call = calls.incrementAndGet();

                    if (call == 2) {
                        // Simulate another worker reclaiming the event
                        // before this worker attempts finalization.
                        event.setStatus("PROCESSING");
                        event.setClaimToken("new-worker-token");
                    }

                    return Optional.of(event);
                });

        processor.processOne(1L);

        assertEquals("PROCESSING", event.getStatus());
        assertEquals(
                "new-worker-token",
                event.getClaimToken()
        );

        // Only the initial claim is saved.
        // The stale worker must not save its final result.
        verify(eventRepository, times(1)).save(event);
    }

    @Test
    void expiredProcessingEventCanBeReclaimed() throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        event.setStatus("PROCESSING");
        event.setProcessingStartedAt(
                LocalDateTime.now().minusMinutes(10)
        );
        event.setClaimToken("old-worker-token");

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order()));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123",
                "order_123",
                12500
        )).thenReturn(false);

        processor.processOne(1L);

        assertEquals("RETRY", event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());

        verify(razorpayService).isPaymentCapturedForOrder(
                "pay_123",
                "order_123",
                12500
        );
    }
    @Test
    void abandonedProcessingEventAtRetryLimitMovesDirectlyToManualReview()
            throws Exception {

        RazorpayWebhookEvent event = event(capturedPayload(12500));
        event.setStatus("PROCESSING");
        event.setAttemptCount(8);
        event.setProcessingStartedAt(
                LocalDateTime.now().minusMinutes(10)
        );
        event.setClaimToken("abandoned-worker-token");

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        processor.processOne(1L);

        assertEquals("MANUAL_REVIEW", event.getStatus());
        assertTrue(event.getProcessingError().contains("Retry limit reached"));
        assertNull(event.getNextAttemptAt());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());
        assertEquals(8, event.getAttemptCount());

        verify(eventRepository, times(1)).save(event);
        verifyNoInteractions(
                orderRepository,
                razorpayService,
                paymentVerificationService
        );
    }

    @Test
    void retryLimitMovesEventToManualReview() throws Exception {

        RazorpayWebhookEvent event =
                event(capturedPayload(12500));

        // Seven previous attempts; this claim becomes attempt eight.
        event.setAttemptCount(7);

        when(eventRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(event));

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order()));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123",
                "order_123",
                12500
        )).thenReturn(false);

        processor.processOne(1L);

        assertEquals(8, event.getAttemptCount());
        assertEquals("MANUAL_REVIEW", event.getStatus());
        assertTrue(
                event.getProcessingError().contains("Retry limit reached")
        );
        assertNull(event.getNextAttemptAt());
        assertNull(event.getClaimToken());
        assertNull(event.getProcessingStartedAt());

        verifyNoInteractions(paymentVerificationService);
    }
}

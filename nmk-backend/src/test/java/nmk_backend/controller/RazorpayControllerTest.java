
package nmk_backend.controller;

import nmk_backend.dto.VerifyPaymentRequest;
import nmk_backend.entity.Order;
import nmk_backend.exception.PaymentConfigurationException;
import nmk_backend.exception.PaymentConfirmationConflictException;
import nmk_backend.repository.OrderRepository;
import nmk_backend.service.PaymentVerificationService;
import nmk_backend.service.RazorpayService;
import nmk_backend.service.SmsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayControllerTest {

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentVerificationService paymentVerificationService;

    @Mock
    private SmsService smsService;

    @Mock
    private Order order;

    private RazorpayController controller;

    @BeforeEach
    void setUp() {
        controller = new RazorpayController(
                razorpayService,
                orderRepository,
                paymentVerificationService,
                smsService,
                "https://nmk.example.com/"
        );
    }

    private VerifyPaymentRequest validRequest() {
        VerifyPaymentRequest request = new VerifyPaymentRequest();
        request.setRazorpayOrderId("order_123");
        request.setRazorpayPaymentId("pay_123");
        request.setRazorpaySignature("valid_signature");
        return request;
    }

    private void prepareValidProviderAndOrder() throws Exception {
        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenReturn(true);

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        when(order.getTotalAmount())
                .thenReturn(new BigDecimal("100.00"));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123", "order_123", 10000
        )).thenReturn(true);
    }

    private void prepareConfirmedOrder() {
        when(order.getId()).thenReturn(123L);
        when(order.getTrackingToken()).thenReturn("tracking-token");
        when(order.getPhone()).thenReturn("9876543210");
    }

    @Test
    void nullRequestReturnsBadRequest() {
        ResponseEntity<?> response = controller.verifyPayment(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(razorpayService);
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(paymentVerificationService);
        verifyNoInteractions(smsService);
    }

    @Test
    void invalidSignatureReturnsBadRequest() throws Exception {
        VerifyPaymentRequest request = validRequest();

        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenReturn(false);

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(paymentVerificationService);
        verifyNoInteractions(smsService);
    }

    @Test
    void missingOrderReturnsNotFound() throws Exception {
        VerifyPaymentRequest request = validRequest();

        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenReturn(true);

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(paymentVerificationService);
        verifyNoInteractions(smsService);
    }

    @Test
    void providerExceptionReturnsBadGateway() throws Exception {
        VerifyPaymentRequest request = validRequest();

        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenThrow(new RuntimeException("Provider unavailable"));

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(paymentVerificationService);
    }

    @Test
    void missingCredentialsReturnsServiceUnavailable() throws Exception {
        VerifyPaymentRequest request = validRequest();

        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenThrow(new PaymentConfigurationException("Credentials missing"));

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode()
        );

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(paymentVerificationService);
    }

    @Test
    void uncapturedPaymentReturnsBadRequest() throws Exception {
        VerifyPaymentRequest request = validRequest();

        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenReturn(true);

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        when(order.getTotalAmount())
                .thenReturn(new BigDecimal("100.00"));

        when(razorpayService.isPaymentCapturedForOrder(
                "pay_123", "order_123", 10000
        )).thenReturn(false);

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(paymentVerificationService);
        verifyNoInteractions(smsService);
    }
    @Test
    void fractionalPaiseAmountReturnsInternalServerError()
            throws Exception {

        VerifyPaymentRequest request = validRequest();

        when(razorpayService.verifySignature(
                "order_123", "pay_123", "valid_signature"
        )).thenReturn(true);

        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        when(order.getTotalAmount())
                .thenReturn(new BigDecimal("100.001"));

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode()
        );

        verify(razorpayService, never())
                .isPaymentCapturedForOrder(anyString(), anyString(), anyInt());

        verifyNoInteractions(paymentVerificationService);
        verifyNoInteractions(smsService);
    }

    @Test
    void confirmationConflictReturnsConflict() throws Exception {
        VerifyPaymentRequest request = validRequest();
        prepareValidProviderAndOrder();

        when(paymentVerificationService.confirmPayment(
                "order_123", "pay_123"
        )).thenThrow(new PaymentConfirmationConflictException(
                "A different payment is already recorded."
        ));

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verifyNoInteractions(smsService);
    }

    @Test
    void successfulFirstConfirmationReturnsTrackingDetailsAndSendsSms()
            throws Exception {

        VerifyPaymentRequest request = validRequest();
        prepareValidProviderAndOrder();
        prepareConfirmedOrder();

        when(paymentVerificationService.confirmPayment(
                "order_123", "pay_123"
        )).thenReturn(
                new PaymentVerificationService.ConfirmationResult(order, true)
        );

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(true, body.get("verified"));
        assertEquals(123L, body.get("databaseOrderId"));
        assertEquals("tracking-token", body.get("trackingToken"));
        assertEquals(
                "https://nmk.example.com/track/tracking-token",
                body.get("trackingUrl")
        );

        verify(smsService).sendOrderNotification(
                "9876543210",
                "123",
                "CONFIRMED",
                "",
                "",
                "https://nmk.example.com/track/tracking-token"
        );
    }

    @Test
    void idempotentRetryDoesNotSendAnotherSms() throws Exception {
        VerifyPaymentRequest request = validRequest();
        prepareValidProviderAndOrder();
        when(order.getId()).thenReturn(123L);
        when(order.getTrackingToken()).thenReturn("tracking-token");

        when(paymentVerificationService.confirmPayment(
                "order_123", "pay_123"
        )).thenReturn(
                new PaymentVerificationService.ConfirmationResult(order, false)
        );

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(true, body.get("verified"));
        assertEquals("Order already paid", body.get("message"));

        verify(smsService, never()).sendOrderNotification(
                anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()
        );
    }

    @Test
    void smsFailureDoesNotReverseSuccessfulPayment() throws Exception {
        VerifyPaymentRequest request = validRequest();
        prepareValidProviderAndOrder();
        prepareConfirmedOrder();

        when(paymentVerificationService.confirmPayment(
                "order_123", "pay_123"
        )).thenReturn(
                new PaymentVerificationService.ConfirmationResult(order, true)
        );

        doThrow(new RuntimeException("SMS provider unavailable"))
                .when(smsService)
                .sendOrderNotification(
                        anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString()
                );

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(true, body.get("verified"));

        verify(paymentVerificationService)
                .confirmPayment("order_123", "pay_123");
    }
}

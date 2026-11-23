
package nmk_backend.service;

import com.razorpay.Payment;
import com.razorpay.PaymentClient;
import com.razorpay.RazorpayClient;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RazorpayServiceTest {

    private RazorpayService razorpayService;

    @BeforeEach
    void setUp() {
        razorpayService = new RazorpayService();

        ReflectionTestUtils.setField(razorpayService, "keyId", "test_key");
        ReflectionTestUtils.setField(razorpayService, "keySecret", "test_secret");
        ReflectionTestUtils.setField(razorpayService, "webhookSecret", "test_webhook_secret");
    }

    @Test
    void requirePaymentCredentialsRejectsMissingKeyId() {
        ReflectionTestUtils.setField(razorpayService, "keyId", "");

        assertThrows(
                nmk_backend.exception.PaymentConfigurationException.class,
                () -> razorpayService.requirePaymentCredentials()
        );
    }

    @Test
    void requirePaymentCredentialsRejectsMissingKeySecret() {
        ReflectionTestUtils.setField(razorpayService, "keySecret", "");

        assertThrows(
                nmk_backend.exception.PaymentConfigurationException.class,
                () -> razorpayService.requirePaymentCredentials()
        );
    }

    @Test
    void requirePaymentCredentialsAcceptsConfiguredCredentials() {
        assertDoesNotThrow(
                () -> razorpayService.requirePaymentCredentials()
        );
    }

    @Test
    void createOrderRejectsMissingCredentialsBeforeApiCall() {
        ReflectionTestUtils.setField(razorpayService, "keyId", "");

        assertThrows(
                nmk_backend.exception.PaymentConfigurationException.class,
                () -> razorpayService.createOrder(10000)
        );
    }

    @Test
    void capturedPaymentWithCorrectDetailsIsAccepted() throws Exception {
        assertTrue(verifyPayment(
                12500, "captured", "order_123", "INR"
        ));
    }

    @Test
    void capturedPaymentWithStringAmountIsAccepted() throws Exception {
        assertTrue(verifyPayment(
                "12500", "captured", "order_123", "INR"
        ));
    }

    @Test
    void wrongAmountIsRejected() throws Exception {
        assertFalse(verifyPayment(
                15000, "captured", "order_123", "INR"
        ));
    }

    @Test
    void wrongOrderIdIsRejected() throws Exception {
        assertFalse(verifyPayment(
                12500, "captured", "order_wrong", "INR"
        ));
    }

    @Test
    void wrongCurrencyIsRejected() throws Exception {
        assertFalse(verifyPayment(
                12500, "captured", "order_123", "USD"
        ));
    }

    @Test
    void uncapturedPaymentIsRejected() throws Exception {
        assertFalse(verifyPayment(
                12500, "authorized", "order_123", "INR"
        ));
    }

    @Test
    void malformedAmountIsRejected() throws Exception {
        assertFalse(verifyPayment(
                "invalid", "captured", "order_123", "INR"
        ));
    }

    @Test
    void fractionalAmountIsRejected() throws Exception {
        assertFalse(verifyPayment(
                new BigDecimal("12500.5"),
                "captured",
                "order_123",
                "INR"
        ));
    }

    @Test
    void oversizedAmountIsRejected() throws Exception {
        assertFalse(verifyPayment(
                "999999999999999999999",
                "captured",
                "order_123",
                "INR"
        ));
    }

    private boolean verifyPayment(
            Object amount,
            String status,
            String orderId,
            String currency
    ) throws Exception {

        PaymentClient paymentClient = mock(PaymentClient.class);

        JSONObject paymentJson = new JSONObject()
                .put("status", status)
                .put("order_id", orderId)
                .put("amount", amount)
                .put("currency", currency);

        when(paymentClient.fetch("pay_123"))
                .thenReturn(new Payment(paymentJson));

        try (MockedConstruction<RazorpayClient> mocked =
                     mockConstruction(
                             RazorpayClient.class,
                             (client, context) -> {
                                 client.payments = paymentClient;
                             }
                     )) {

            return razorpayService.isPaymentCapturedForOrder(
                    "pay_123",
                    "order_123",
                    12500
            );
        }
    }
}

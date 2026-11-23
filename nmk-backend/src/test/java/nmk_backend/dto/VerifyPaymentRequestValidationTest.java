package nmk_backend.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyPaymentRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsProviderIdentifierFormats() {
        VerifyPaymentRequest request = validRequest();
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMalformedProviderIdentifiers() {
        VerifyPaymentRequest request = validRequest();
        request.setRazorpayOrderId("order/invalid");
        request.setRazorpayPaymentId("pay invalid");
        request.setRazorpaySignature("not-a-signature");

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMissingOrOversizedIdentifiers() {
        VerifyPaymentRequest request = validRequest();
        request.setRazorpayOrderId(" ");
        request.setRazorpayPaymentId("pay_" + "x".repeat(70));

        assertFalse(validator.validate(request).isEmpty());
    }

    private VerifyPaymentRequest validRequest() {
        VerifyPaymentRequest request = new VerifyPaymentRequest();
        request.setRazorpayOrderId("order_123456789");
        request.setRazorpayPaymentId("pay_123456789");
        request.setRazorpaySignature("a".repeat(64));
        return request;
    }
}

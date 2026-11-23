package nmk_backend.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PosCheckoutRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void rejectsCheckoutCollectionsAndStringsBeyondServiceLimits() {
        PosCheckoutRequest request = validRequest();

        List<PosSaleItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            PosSaleItemRequest item = new PosSaleItemRequest();
            item.setProductId((long) i + 1);
            item.setVariantId((long) i + 1);
            item.setQuantity(1);
            items.add(item);
        }
        request.setItems(items);

        List<PosPaymentRequest> payments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            PosPaymentRequest payment = new PosPaymentRequest();
            payment.setPaymentMethod("CASH");
            payment.setAmount(BigDecimal.ONE);
            payments.add(payment);
        }
        request.setPayments(payments);

        request.setCustomerName("x".repeat(151));
        request.setCustomerPhone("1".repeat(31));
        request.setIdempotencyKey("k".repeat(101));

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(invalidFields.contains("items"));
        assertTrue(invalidFields.contains("payments"));
        assertTrue(invalidFields.contains("customerName"));
        assertTrue(invalidFields.contains("customerPhone"));
        assertTrue(invalidFields.contains("idempotencyKey"));
    }

    @Test
    void acceptsCheckoutAtExistingServiceLimits() {
        PosCheckoutRequest request = validRequest();

        List<PosSaleItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            PosSaleItemRequest item = new PosSaleItemRequest();
            item.setProductId((long) i + 1);
            item.setVariantId((long) i + 1);
            item.setQuantity(1);
            items.add(item);
        }
        request.setItems(items);

        List<PosPaymentRequest> payments = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PosPaymentRequest payment = new PosPaymentRequest();
            payment.setPaymentMethod("CASH");
            payment.setAmount(BigDecimal.ONE);
            payments.add(payment);
        }
        request.setPayments(payments);

        assertTrue(validator.validate(request).isEmpty());
    }

    private PosCheckoutRequest validRequest() {
        PosCheckoutRequest request = new PosCheckoutRequest();
        request.setLocationId(1L);
        request.setShiftId(1L);
        request.setCustomerName("Customer");
        request.setCustomerPhone("9876543210");
        request.setDiscountAmount(BigDecimal.ZERO);
        request.setIdempotencyKey("checkout-validation-test");

        PosSaleItemRequest item = new PosSaleItemRequest();
        item.setProductId(1L);
        item.setVariantId(1L);
        item.setQuantity(1);
        request.setItems(List.of(item));

        PosPaymentRequest payment = new PosPaymentRequest();
        payment.setPaymentMethod("CASH");
        payment.setAmount(BigDecimal.ONE);
        request.setPayments(List.of(payment));
        return request;
    }
}

package nmk_backend.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminOrderRequestValidationTest {

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
    void rejectsBlankOrOversizedOrderStatus() {
        Set<String> blankFields = validator.validate(
                new UpdateOrderStatusRequest("  ")
        ).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        Set<String> oversizedFields = validator.validate(
                new UpdateOrderStatusRequest("X".repeat(31))
        ).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(blankFields.contains("status"));
        assertTrue(oversizedFields.contains("status"));
        assertTrue(validator.validate(new UpdateOrderStatusRequest("SHIPPED")).isEmpty());
    }

    @Test
    void rejectsBlankOrOversizedDeliveryDetails() {
        Set<String> blankFields = validator.validate(
                new UpdateDeliveryDetailsRequest(" ", " ")
        ).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        Set<String> oversizedFields = validator.validate(
                new UpdateDeliveryDetailsRequest("C".repeat(101), "T".repeat(151))
        ).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(blankFields.contains("courierName"));
        assertTrue(blankFields.contains("trackingNumber"));
        assertTrue(oversizedFields.contains("courierName"));
        assertTrue(oversizedFields.contains("trackingNumber"));
        assertTrue(validator.validate(
                new UpdateDeliveryDetailsRequest("Courier", "TRACK-123")
        ).isEmpty());
    }
}

package nmk_backend.entity;

import nmk_backend.dto.AppointmentStatusRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void rejectsMissingRequiredBookingFields() {
        Appointment appointment = new Appointment();

        assertFalse(validator.validate(appointment).isEmpty());
    }

    @Test
    void rejectsInvalidEmailAndOversizedCustomerFields() {
        Appointment appointment = validAppointment();
        appointment.setCustomerName("N".repeat(256));
        appointment.setEmail("not-an-email");

        assertFalse(validator.validate(appointment).isEmpty());
    }

    @Test
    void acceptsValidBookingFields() {
        assertTrue(validator.validate(validAppointment()).isEmpty());
    }

    @Test
    void acceptsOnlySupportedAppointmentStatuses() {
        for (String status : java.util.List.of("PENDING", "CONFIRMED", "COMPLETED", "CANCELLED")) {
            assertTrue(validator.validate(new AppointmentStatusRequest(status)).isEmpty());
        }
    }

    @Test
    void rejectsBlankUnknownOrCaseAlteredAppointmentStatuses() {
        for (String status : java.util.Arrays.asList(null, "", "BOOKED", "confirmed")) {
            assertFalse(validator.validate(new AppointmentStatusRequest(status)).isEmpty());
        }
    }

    private Appointment validAppointment() {
        Appointment appointment = new Appointment();
        appointment.setCustomerName("Customer");
        appointment.setPhone("9876543210");
        appointment.setEmail("customer@example.com");
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setAppointmentTime(LocalTime.of(9, 0));
        return appointment;
    }
}

package nmk_backend.controller;

import nmk_backend.entity.Appointment;
import nmk_backend.dto.AppointmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import java.util.List;
import nmk_backend.repository.AppointmentRepository;
import nmk_backend.service.AdminActionAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AppointmentControllerTest {

    private final AppointmentRepository repository = mock(AppointmentRepository.class);
    private final AdminActionAuthorizationService authorizationService =
            mock(AdminActionAuthorizationService.class);
    private final AppointmentController controller =
            new AppointmentController(repository, authorizationService, "Asia/Kolkata");

    @Test
    void rejectsAppointmentDateBeforeShopLocalToday() {
        Appointment appointment = validAppointment();
        appointment.setAppointmentDate(
                LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1));

        var response = controller.bookAppointment(appointment);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsAppointmentOnShopLocalToday() {
        Appointment appointment = validAppointment();
        appointment.setAppointmentDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        when(repository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.bookAppointment(appointment);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", ((AppointmentResponse) response.getBody()).status());
        verify(repository).save(appointment);
    }

    @Test
    void returnsAppointmentsInDeterministicPages() {
        Appointment appointment = validAppointment();
        appointment.setId(12L);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(appointment)));

        var response = controller.getAllAppointments(0, 25);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Page<?> page = (Page<?>) response.getBody();
        assertNotNull(page);
        assertEquals(1, page.getTotalElements());
        assertEquals(12L, ((AppointmentResponse) page.getContent().get(0)).id());
    }

    @Test
    void rejectsInvalidAppointmentPageBounds() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.getAllAppointments(-1, 25).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.getAllAppointments(0, 0).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.getAllAppointments(0, 101).getStatusCode());
        verifyNoInteractions(repository);
    }

    private Appointment validAppointment() {
        Appointment appointment = new Appointment();
        appointment.setCustomerName("Test Customer");
        appointment.setPhone("9876543210");
        appointment.setAppointmentDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        appointment.setAppointmentTime(LocalTime.of(9, 0));
        return appointment;
    }
}

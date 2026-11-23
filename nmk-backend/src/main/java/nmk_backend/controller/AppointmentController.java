
package nmk_backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

import nmk_backend.entity.Appointment;
import nmk_backend.dto.AppointmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import nmk_backend.dto.AppointmentStatusRequest;
import nmk_backend.repository.AppointmentRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final AdminActionAuthorizationService authorizationService;

    private final AppointmentRepository appointmentRepository;
    private final ZoneId businessZone;

    public AppointmentController(
            AppointmentRepository appointmentRepository,
            AdminActionAuthorizationService authorizationService,
            @Value("${app.business.time-zone:Asia/Kolkata}") String businessTimeZone
    ) {
        this.appointmentRepository = appointmentRepository;
        this.authorizationService = authorizationService;
        this.businessZone = ZoneId.of(businessTimeZone);
    }
    @DeleteMapping("/clear")
    @Transactional
    public ResponseEntity<?> clearAllAppointments(
            @RequestHeader(
                    value = "X-Admin-Action-Authorization",
                    required = false
            ) String authorizationToken,
            Authentication authentication
    ) {
        try {
            if (authentication == null) {
                return ResponseEntity.status(401).body(
                        Map.of("message", "Admin login required")
                );
            }

            authorizationService.consumeAuthorization(
                    authentication.getName(),
                    authorizationToken,
                    "CLEAR_APPOINTMENTS"
            );

            long deletedCount = appointmentRepository.count();

            appointmentRepository.deleteAll();

            return ResponseEntity.ok(Map.of(
                    "message", "All appointments deleted successfully",
                    "deletedCount", deletedCount
            ));

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(
                    Map.of("message", exception.getMessage())
            );
        }
    }

    // Customer: Book an appointment
    @PostMapping
    public ResponseEntity<?> bookAppointment(
            @Valid @RequestBody Appointment appointment) {

        if (appointment.getCustomerName() == null ||
                appointment.getCustomerName().isBlank() ||
                appointment.getPhone() == null ||
                appointment.getPhone().isBlank() ||
                appointment.getAppointmentDate() == null ||
                appointment.getAppointmentTime() == null) {

            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Please provide all required details."));
        }

        if (appointment.getAppointmentDate().isBefore(LocalDate.now(businessZone))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Appointment date cannot be in the past."));
        }

        LocalTime time = appointment.getAppointmentTime();

        Set<LocalTime> allowedSlots = Set.of(
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                LocalTime.of(15, 0)
        );

        if (!allowedSlots.contains(time)) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "Choose one of the available slots: 9 AM, 12 PM, or 3 PM."
                    ));
        }

        appointment.setId(null);
        appointment.setStatus("PENDING");
        appointment.setCreatedAt(null);

        Appointment saved = appointmentRepository.save(appointment);
        return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(saved));
    }

    // Admin: View appointments in bounded, deterministic pages.
    @GetMapping
    public ResponseEntity<?> getAllAppointments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Page must be non-negative and size must be between 1 and 100"
            ));
        }

        Page<AppointmentResponse> result = appointmentRepository
                .findAll(PageRequest.of(page, size, Sort.by(
                        Sort.Order.asc("appointmentDate"),
                        Sort.Order.asc("appointmentTime"),
                        Sort.Order.asc("id")
                )))
                .map(AppointmentResponse::from);
        return ResponseEntity.ok(result);
    }

    // Admin: Update appointment status
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody AppointmentStatusRequest request) {

        Appointment appointment = appointmentRepository.findById(id)
                .orElse(null);

        if (appointment == null) {
            return ResponseEntity.notFound().build();
        }

        String status = request.status();
        appointment.setStatus(status);

        return ResponseEntity.ok(AppointmentResponse.from(appointmentRepository.save(appointment)));
    }
}

package nmk_backend.dto;

import nmk_backend.entity.Appointment;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record AppointmentResponse(
        Long id,
        String customerName,
        String phone,
        String email,
        LocalDate appointmentDate,
        LocalTime appointmentTime,
        String status,
        LocalDateTime createdAt
) {
    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getCustomerName(),
                appointment.getPhone(),
                appointment.getEmail(),
                appointment.getAppointmentDate(),
                appointment.getAppointmentTime(),
                appointment.getStatus(),
                appointment.getCreatedAt()
        );
    }
}

package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AppointmentStatusRequest(
        @NotBlank(message = "Appointment status is required")
        @Pattern(
                regexp = "PENDING|CONFIRMED|COMPLETED|CANCELLED",
                message = "Invalid appointment status"
        )
        String status
) {}

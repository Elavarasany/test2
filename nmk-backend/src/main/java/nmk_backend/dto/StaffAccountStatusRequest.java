package nmk_backend.dto;

import jakarta.validation.constraints.NotNull;

public record StaffAccountStatusRequest(@NotNull Boolean enabled) {
}
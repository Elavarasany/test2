package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PosRegisterCreateRequest(
        @NotNull @Positive Long locationId,
        @NotBlank @Size(min = 2, max = 40)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String registerCode,
        @NotBlank @Size(max = 120) String name
) {
}
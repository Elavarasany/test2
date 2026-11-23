package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record StockCountStartRequest(
        @NotNull @Positive Long locationId,
        @NotBlank @Size(max = 400) String reason
) {
}
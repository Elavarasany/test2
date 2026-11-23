package nmk_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PosShiftOpenRequest(
        @NotNull @Positive Long registerId,
        @NotNull @DecimalMin("0.00") BigDecimal openingFloat,
        @NotBlank String idempotencyKey
) {
}
package nmk_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PosShiftCloseRequest(
        @NotNull @DecimalMin("0.00") BigDecimal closingCashCount,
        @Size(max = 500) String reason
) {
}
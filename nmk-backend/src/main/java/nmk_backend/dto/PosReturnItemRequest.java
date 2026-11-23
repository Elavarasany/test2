package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PosReturnItemRequest(
        @NotNull @Positive Long saleItemId,
        @NotNull @Positive Integer quantity,
        @NotBlank String restockDisposition,
        @Size(max = 500) String dispositionReason
) {
}
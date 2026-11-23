package nmk_backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InventoryReorderLevelRequest(
        @NotNull @Positive Long locationId,
        @NotNull @Positive Long variantId,
        @NotNull @Min(0) Integer reorderLevel
) {
}
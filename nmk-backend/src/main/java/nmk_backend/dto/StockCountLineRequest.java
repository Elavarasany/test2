package nmk_backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockCountLineRequest(
        @NotNull @Min(0) Integer countedQuantity
) {
}
package nmk_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record PurchaseOrderItemRequest(
        @NotNull @Positive Long variantId,
        @NotNull @Positive Integer orderedQuantity,
        @NotNull @DecimalMin("0.01") BigDecimal unitCost
) {
}
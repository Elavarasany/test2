package nmk_backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GoodsReceiptItemRequest(
        @NotNull @Positive Long purchaseOrderItemId,
        @NotNull @Positive Integer quantity
) {
}
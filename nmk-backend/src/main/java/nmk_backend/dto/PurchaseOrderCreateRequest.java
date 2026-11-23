package nmk_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PurchaseOrderCreateRequest(
        @NotNull @Positive Long supplierId,
        @NotNull @Positive Long locationId,
        @Size(max = 500) String notes,
        @NotEmpty @Size(max = 100) List<@Valid PurchaseOrderItemRequest> items,
        @NotBlank @Size(max = 100) String idempotencyKey
) {
}
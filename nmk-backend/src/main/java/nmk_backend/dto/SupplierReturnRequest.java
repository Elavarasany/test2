package nmk_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SupplierReturnRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotEmpty @Size(max = 100) List<@Valid SupplierReturnItemRequest> items
) {
}
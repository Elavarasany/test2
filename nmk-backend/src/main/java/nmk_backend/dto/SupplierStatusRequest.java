package nmk_backend.dto;

import jakarta.validation.constraints.NotNull;

public record SupplierStatusRequest(@NotNull Boolean active) {
}
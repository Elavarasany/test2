package nmk_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record ProductVariantRequest(
        @NotBlank(message = "SKU is required")
        @Size(max = 100, message = "SKU must not exceed 100 characters")
        String sku,

        @Size(max = 100, message = "Barcode must not exceed 100 characters")
        String barcode,

        @NotBlank(message = "Variant name is required")
        @Size(max = 255, message = "Variant name must not exceed 255 characters")
        String variantName,

        Map<String, Object> attributes,

        @DecimalMin(value = "0.01", message = "Selling price override must be greater than zero")
        BigDecimal sellingPriceOverride
) {
}

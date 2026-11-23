package nmk_backend.dto;

import nmk_backend.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProductVariantResponse(
        Long id,
        Long productId,
        String sku,
        String barcode,
        String variantName,
        Map<String, Object> attributes,
        BigDecimal sellingPriceOverride,
        boolean active,
        LocalDateTime createdAt
) {
    public static ProductVariantResponse from(ProductVariant variant) {
        return new ProductVariantResponse(
                variant.getId(),
                variant.getProduct().getId(),
                variant.getSku(),
                variant.getBarcode(),
                variant.getVariantName(),
                new LinkedHashMap<>(variant.getAttributes()),
                variant.getSellingPriceOverride(),
                variant.isActive(),
                variant.getCreatedAt()
        );
    }
}

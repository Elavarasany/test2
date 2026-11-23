package nmk_backend.dto;

import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public record PosCatalogResponse(
        Long locationId,
        boolean stockCountLocked,
        List<ProductItem> products
) {
    public record ProductItem(
            Long productId,
            String name,
            String category,
            String gender,
            String imageUrl,
            BigDecimal basePrice,
            List<VariantItem> variants
    ) {}

    public record VariantItem(
            Long variantId,
            String sku,
            String barcode,
            String variantName,
            Map<String, Object> attributes,
            BigDecimal sellingPrice,
            int onHandQuantity,
            int reservedQuantity,
            int availableQuantity,
            int reorderLevel
    ) {}

    public static ProductItem product(Product product, List<VariantItem> variants) {
        return new ProductItem(
                product.getId(),
                product.getName(),
                product.getCategory(),
                product.getGender(),
                product.getImageUrl(),
                product.getPrice(),
                variants
        );
    }

    public static VariantItem variant(
            ProductVariant variant,
            BigDecimal price,
            int onHand,
            int reserved,
            int reorderLevel
    ) {
        return new VariantItem(
                variant.getId(),
                variant.getSku(),
                variant.getBarcode(),
                variant.getVariantName(),
                new LinkedHashMap<>(variant.getAttributes()),
                price,
                onHand,
                reserved,
                onHand - reserved,
                reorderLevel
        );
    }
}
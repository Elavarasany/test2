package nmk_backend.dto;

import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CustomerProductCatalogResponse(
        List<ProductItem> products
) {
    public record ProductItem(
            Long id,
            Long version,
            String tryOnImageUrl,
            String name,
            String category,
            String gender,
            String description,
            BigDecimal price,
            Integer stockQuantity,
            String imageUrl,
            boolean active,
            LocalDateTime createdAt,
            Map<String, Object> specifications,
            List<VariantItem> variants
    ) {}

    public record VariantItem(
            Long id,
            String variantName,
            Map<String, Object> attributes,
            BigDecimal price,
            int availableQuantity
    ) {}

    public static ProductItem from(
            Product product,
            List<VariantItem> variants,
            BigDecimal displayPrice,
            int availableQuantity
    ) {
        return new ProductItem(
                product.getId(),
                product.getVersion(),
                product.getTryOnImageUrl(),
                product.getName(),
                product.getCategory(),
                product.getGender(),
                product.getDescription(),
                displayPrice,
                availableQuantity,
                product.getImageUrl(),
                product.isActive(),
                product.getCreatedAt(),
                product.getSpecifications(),
                variants
        );
    }

    public static VariantItem variant(
            ProductVariant variant,
            BigDecimal price,
            int availableQuantity
    ) {
        return new VariantItem(
                variant.getId(),
                variant.getVariantName(),
                new LinkedHashMap<>(variant.getAttributes()),
                price,
                availableQuantity
        );
    }
}

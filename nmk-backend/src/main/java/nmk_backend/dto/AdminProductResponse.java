package nmk_backend.dto;

import nmk_backend.entity.Product;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record AdminProductResponse(
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
        Map<String, Object> specifications
) {
    public static AdminProductResponse from(Product product) {
        return new AdminProductResponse(
                product.getId(),
                product.getVersion(),
                product.getTryOnImageUrl(),
                product.getName(),
                product.getCategory(),
                product.getGender(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getImageUrl(),
                product.isActive(),
                product.getCreatedAt(),
                product.getSpecifications()
        );
    }
}

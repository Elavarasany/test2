package nmk_backend.service;

import nmk_backend.dto.ProductVariantRequest;
import nmk_backend.entity.Product;
import nmk_backend.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class ProductVariantLifecycleMySqlTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductVariantService productVariantService;

    @Test
    void deactivatedProductCannotReceiveNewVariants() {
        Product product = new Product();
        product.setName("Inactive Parent Variant Test " + UUID.randomUUID());
        product.setPrice(new BigDecimal("999.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        Long productId = product.getId();
        productService.deleteProduct(productId);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> productVariantService.createVariant(
                        productId,
                        new ProductVariantRequest(
                                "INACTIVE-PARENT-" + UUID.randomUUID(),
                                null,
                                "Must Be Rejected",
                                Map.of(),
                                null
                        )
                )
        );

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(productRepository.findById(productId).isPresent());
        assertFalse(productRepository.findById(productId).orElseThrow().isActive());
    }
}

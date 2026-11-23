package nmk_backend.service;

import nmk_backend.dto.ProductVariantRequest;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductVariantServiceTest {

    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private ProductVariantService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        service = new ProductVariantService(productRepository, variantRepository);
    }

    @Test
    void createsVariantWithNormalizedSkuAndOptionalAttributes() {
        Product product = new Product();
        product.setId(10L);
        product.setActive(true);

        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(variantRepository.existsBySku("FRAME-BLK")).thenReturn(false);
        when(variantRepository.existsByBarcode("8901234567890")).thenReturn(false);
        when(variantRepository.saveAndFlush(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductVariantRequest request = new ProductVariantRequest(
                " frame-blk ",
                " 8901234567890 ",
                " Black - Medium ",
                Map.of("color", "Black", "size", "Medium"),
                new BigDecimal("125.00")
        );

        ProductVariant result = service.createVariant(10L, request);

        assertEquals("FRAME-BLK", result.getSku());
        assertEquals("8901234567890", result.getBarcode());
        assertEquals("Black - Medium", result.getVariantName());
        assertEquals(product, result.getProduct());
        assertEquals(new BigDecimal("125.00"), result.getSellingPriceOverride());
        assertEquals("Black", result.getAttributes().get("color"));
        assertTrue(result.isActive());
        verify(variantRepository).saveAndFlush(any(ProductVariant.class));
    }

    @Test
    void rejectsDuplicateSku() {
        Product product = new Product();
        product.setId(10L);
        product.setActive(true);
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(variantRepository.existsBySku("FRAME-BLK")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createVariant(10L, new ProductVariantRequest(
                        "frame-blk", null, "Black", Map.of(), null
                ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(variantRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInactiveParentProduct() {
        Product product = new Product();
        product.setId(10L);
        product.setActive(false);
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createVariant(10L, new ProductVariantRequest(
                        "FRAME-BLK", null, "Black", Map.of(), null
                ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verifyNoInteractions(variantRepository);
    }

    @Test
    void rejectsDuplicateBarcode() {
        Product product = new Product();
        product.setId(10L);
        product.setActive(true);
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(variantRepository.existsBySku("FRAME-BLK")).thenReturn(false);
        when(variantRepository.existsByBarcode("8901234567890")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createVariant(10L, new ProductVariantRequest(
                        "FRAME-BLK", "8901234567890", "Black", Map.of(), null
                ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(variantRepository, never()).saveAndFlush(any());
    }
}

package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.exception.InvalidInventoryOperationException;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PosCatalogServiceTest {

    private InventoryLocationRepository locationRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private InventoryBalanceRepository balanceRepository;
    private PosCatalogService service;

    @BeforeEach
    void setUp() {
        locationRepository = mock(InventoryLocationRepository.class);
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        balanceRepository = mock(InventoryBalanceRepository.class);

        service = new PosCatalogService(
                locationRepository,
                productRepository,
                variantRepository,
                balanceRepository
        );
    }

    @Test
    void catalogUsesVariantPriceAndLocationSpecificAvailableStock() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setName("Main Store");
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(20L);
        variant.setProduct(product);
        variant.setSku("FRAME-BLK");
        variant.setBarcode("8901234567890");
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setSellingPriceOverride(new BigDecimal("125.00"));
        variant.setActive(true);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(3);

        when(locationRepository.findById(1L)).thenReturn(java.util.Optional.of(location));
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of(balance));
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(productRepository.findAll()).thenReturn(List.of(product));

        var result = service.getCatalog(1L);

        assertEquals(1L, result.locationId());
        assertFalse(result.stockCountLocked());
        assertEquals(1, result.products().size());
        var item = result.products().get(0).variants().get(0);
        assertEquals(new BigDecimal("125.00"), item.sellingPrice());
        assertEquals(8, item.onHandQuantity());
        assertEquals(3, item.reservedQuantity());
        assertEquals(5, item.availableQuantity());
        assertEquals("8901234567890", item.barcode());
    }

    @Test
    void catalogRejectsInactiveLocation() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setActive(false);
        when(locationRepository.findById(1L)).thenReturn(java.util.Optional.of(location));

        assertThrows(InvalidInventoryOperationException.class, () -> service.getCatalog(1L));
        verifyNoInteractions(balanceRepository, productRepository, variantRepository);
    }
}

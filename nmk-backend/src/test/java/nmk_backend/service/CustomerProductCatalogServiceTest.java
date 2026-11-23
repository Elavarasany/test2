package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerProductCatalogServiceTest {

    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private InventoryLocationRepository locationRepository;
    private InventoryBalanceRepository balanceRepository;
    private CustomerProductCatalogService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        balanceRepository = mock(InventoryBalanceRepository.class);

        service = new CustomerProductCatalogService(
                productRepository,
                variantRepository,
                locationRepository,
                balanceRepository,
                "AVP-01"
        );
    }

    @Test
    void returnsActiveVariantsWithRealIdsAndLocationAvailableStock() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setSellingPriceOverride(new BigDecimal("125.00"));
        variant.setActive(true);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(3);

        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of(balance));
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(productRepository.findAll()).thenReturn(List.of(product));

        var result = service.getActiveProducts();

        assertEquals(1, result.size());
        var item = result.get(0);
        assertEquals(10L, item.id());
        assertEquals(new BigDecimal("125.00"), item.price());
        assertEquals(5, item.stockQuantity());
        assertEquals(1, item.variants().size());
        assertEquals(25L, item.variants().get(0).id());
        assertEquals(new BigDecimal("125.00"), item.variants().get(0).price());
        assertEquals(5, item.variants().get(0).availableQuantity());
        assertEquals("Black", item.variants().get(0).attributes().get("color"));
    }

    @Test
    void returnsZeroAvailableWhenVariantHasNoLocationBalance() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setActive(true);

        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of());
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(productRepository.findAll()).thenReturn(List.of(product));

        var result = service.getActiveProducts();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).stockQuantity());
        assertEquals(0, result.get(0).variants().get(0).availableQuantity());
    }

    @Test
    void hidesPurchasableAvailabilityWhileLocationStockCountIsLocked() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);
        location.setStockCountLocked(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Count Freeze Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(3);

        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of(balance));
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(productRepository.findAll()).thenReturn(List.of(product));

        var result = service.getActiveProducts();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).stockQuantity());
        assertEquals(0, result.get(0).variants().get(0).availableQuantity());
    }


    @Test
    void loadsCustomerCatalogueProductsAndVariantsInBoundedPages() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Paged Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setSellingPriceOverride(new BigDecimal("125.00"));
        variant.setActive(true);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(3);

        Pageable pageable = PageRequest.of(0, 24);
        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(productRepository.findActiveProductsWithActiveVariants(pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 30));
        when(variantRepository.findByProductIdInAndActiveTrue(List.of(10L)))
                .thenReturn(List.of(variant));
        when(balanceRepository.findByLocationIdAndVariantIdIn(1L, List.of(25L)))
                .thenReturn(List.of(balance));

        var page = service.getActiveProducts(pageable);

        assertEquals(30, page.getTotalElements());
        assertEquals(24, page.getSize());
        assertEquals(1, page.getContent().size());
        assertEquals(5, page.getContent().get(0).stockQuantity());
        assertEquals(25L, page.getContent().get(0).variants().get(0).id());
        verify(productRepository, never()).findAll();
        verify(variantRepository, never()).findAll();
        verify(balanceRepository, never()).findByLocationId(1L);
    }

    @Test
    void loadsOnlyRequestedProductAndItsVariantsForPublicDetail() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setSellingPriceOverride(new BigDecimal("125.00"));
        variant.setActive(true);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(3);

        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(variantRepository.findByProductIdAndActiveTrue(10L)).thenReturn(List.of(variant));
        when(balanceRepository.findByLocationIdAndVariantIdIn(1L, List.of(25L)))
                .thenReturn(List.of(balance));

        var result = service.getActiveProductById(10L);

        assertEquals(10L, result.id());
        assertEquals(new BigDecimal("125.00"), result.price());
        assertEquals(5, result.stockQuantity());
        assertEquals(25L, result.variants().get(0).id());
        verify(productRepository, never()).findAll();
        verify(variantRepository, never()).findAll();
        verify(balanceRepository, never()).findByLocationId(1L);
    }

    @Test
    void publicProductDetailReturnsNotFoundForMissingOrInactiveProducts() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);

        Product inactive = new Product();
        inactive.setId(10L);
        inactive.setName("Inactive frame");
        inactive.setPrice(new BigDecimal("100.00"));
        inactive.setActive(false);

        when(productRepository.findById(10L)).thenReturn(Optional.of(inactive));
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.getActiveProductById(10L));
        assertThrows(ResponseStatusException.class,
                () -> service.getActiveProductById(999L));
        assertThrows(ResponseStatusException.class,
                () -> service.getActiveProductById(0L));
        verifyNoInteractions(locationRepository, balanceRepository, variantRepository);
    }

    @Test
    void rejectsMissingCheckoutLocationConfiguration() {
        CustomerProductCatalogService unconfigured = new CustomerProductCatalogService(
                productRepository,
                variantRepository,
                locationRepository,
                balanceRepository,
                " "
        );

        assertThrows(ResponseStatusException.class, unconfigured::getActiveProducts);
        verifyNoInteractions(locationRepository, balanceRepository, variantRepository, productRepository);
    }

    @Test
    void rejectsMissingOrInactiveCheckoutLocation() {
        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, service::getActiveProducts);
        verifyNoInteractions(balanceRepository, variantRepository, productRepository);
    }
}

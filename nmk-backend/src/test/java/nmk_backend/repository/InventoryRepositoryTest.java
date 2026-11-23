package nmk_backend.repository;

import org.springframework.test.annotation.DirtiesContext;

import nmk_backend.entity.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.transaction.annotation.Propagation;

import static org.junit.jupiter.api.Assertions.*;
import nmk_backend.service.InventoryService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;


@SpringBootTest
@Transactional
@ActiveProfiles("test")
class InventoryRepositoryTest {
    @MockitoSpyBean
    private StockMovementRepository movementRepositorySpy;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository variantRepository;

    @Autowired
    private InventoryLocationRepository locationRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Product product;
    private ProductVariant variant;
    private InventoryLocation location;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setName("Inventory Test Product");
        product.setPrice(new BigDecimal("999.00"));
        product.setStockQuantity(10);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        location = new InventoryLocation();
        location.setCode("MAIN");
        location.setName("Main Store");
        location.setActive(true);
        location = locationRepository.saveAndFlush(location);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("TEST-SKU-001");
        variant.setBarcode("TEST-BARCODE-001");
        variant.setVariantName("Standard");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variantRepository.saveAndFlush(variant);

        entityManager.clear();
    }

    @Test
    void productVariantCanBeSavedAndFoundBySku() {
        ProductVariant result = variantRepository
                .findBySku("TEST-SKU-001")
                .orElseThrow();

        assertEquals("TEST-SKU-001", result.getSku());
        assertEquals("Standard", result.getVariantName());
        assertEquals(product.getId(), result.getProduct().getId());
    }

    @Test
    void inventoryLocationCanBeFoundByCode() {
        InventoryLocation result = locationRepository
                .findByCode("MAIN")
                .orElseThrow();

        assertEquals("MAIN", result.getCode());
        assertEquals("Main Store", result.getName());
        assertTrue(result.isActive());
    }

    @Test
    void inventoryBalancePersistsAndCalculatesAvailableQuantity() {
        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(20);
        balance.setReservedQuantity(5);
        balance.setReorderLevel(3);

        balanceRepository.saveAndFlush(balance);
        entityManager.clear();

        InventoryBalance result = balanceRepository
                .findByLocationIdAndVariantId(
                        location.getId(),
                        variant.getId()
                )
                .orElseThrow();

        assertEquals(20, result.getOnHandQuantity());
        assertEquals(5, result.getReservedQuantity());
        assertEquals(15, result.getAvailableQuantity());
        assertEquals(3, result.getReorderLevel());
        assertNotNull(result.getVersion());
    }

    @Test
    void stockMovementPersistsAndCanBeFoundByIdempotencyKey() {
        StockMovement movement = createMovement("TEST-MOVEMENT-001");

        movementRepository.saveAndFlush(movement);
        entityManager.clear();

        StockMovement result = movementRepository
                .findByIdempotencyKey("TEST-MOVEMENT-001")
                .orElseThrow();

        assertEquals("RECEIPT", result.getMovementType());
        assertEquals(10, result.getQuantityDelta());
        assertEquals(10, result.getOnHandAfter());
        assertEquals(0, result.getReservedAfter());
    }

    @Test
    void duplicateSkuIsRejected() {
        ProductVariant duplicate = createVariant(
                "TEST-SKU-001",
                "TEST-BARCODE-002"
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> variantRepository.saveAndFlush(duplicate)
        );
    }

    @Test
    void duplicateLocationCodeIsRejected() {
        InventoryLocation duplicate = new InventoryLocation();
        duplicate.setCode("MAIN");
        duplicate.setName("Duplicate Store");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> locationRepository.saveAndFlush(duplicate)
        );
    }

    @Test
    void duplicateLocationAndVariantBalanceIsRejected() {
        InventoryBalance first = createBalance();
        balanceRepository.saveAndFlush(first);

        InventoryBalance duplicate = createBalance();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> balanceRepository.saveAndFlush(duplicate)
        );
    }

    @Test
    void duplicateMovementIdempotencyKeyIsRejected() {
        movementRepository.saveAndFlush(
                createMovement("DUPLICATE-KEY")
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> movementRepository.saveAndFlush(
                        createMovement("DUPLICATE-KEY")
                )
        );
    }
    @Test
    void inventoryBalanceRejectsNegativeOnHandQuantity() {
        InventoryBalance balance = createBalance();
        balance.setOnHandQuantity(-1);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> balanceRepository.saveAndFlush(balance)
        );
    }

    @Test
    void inventoryBalanceRejectsNegativeReservedQuantity() {
        InventoryBalance balance = createBalance();
        balance.setReservedQuantity(-1);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> balanceRepository.saveAndFlush(balance)
        );
    }

    @Test
    void inventoryBalanceRejectsReservedQuantityGreaterThanOnHand() {
        InventoryBalance balance = createBalance();
        balance.setOnHandQuantity(5);
        balance.setReservedQuantity(6);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> balanceRepository.saveAndFlush(balance)
        );
    }

    @Test
    void inventoryBalanceRejectsNegativeReorderLevel() {
        InventoryBalance balance = createBalance();
        balance.setReorderLevel(-1);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> balanceRepository.saveAndFlush(balance)
        );
    }

    @Test
    void stockMovementRejectsNegativeOnHandAfter() {
        StockMovement movement = createMovement("INVALID-ON-HAND");
        movement.setOnHandAfter(-1);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> movementRepository.saveAndFlush(movement)
        );
    }

    @Test
    void stockMovementRejectsNegativeReservedAfter() {
        StockMovement movement = createMovement("INVALID-RESERVED");
        movement.setReservedAfter(-1);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> movementRepository.saveAndFlush(movement)
        );
    }

    @Test
    void stockMovementRejectsReservedAfterGreaterThanOnHand() {
        StockMovement movement = createMovement("INVALID-RESERVED-OVER-ON-HAND");
        movement.setOnHandAfter(5);
        movement.setReservedAfter(6);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> movementRepository.saveAndFlush(movement)
        );
    }
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedStockMovementPersistenceRollsBackBalanceUpdate() {
        InventoryBalance initialBalance = createBalance();
        initialBalance.setOnHandQuantity(10);
        initialBalance.setReservedQuantity(0);
        balanceRepository.saveAndFlush(initialBalance);

        doThrow(new DataIntegrityViolationException("Simulated movement save failure"))
                .when(movementRepositorySpy)
                .saveAndFlush(any(StockMovement.class));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> inventoryService.receiveStock(
                        location.getId(),
                        variant.getId(),
                        5,
                        "ROLLBACK-TEST-001",
                        null,
                        "Rollback test"
                )
        );

        entityManager.clear();

        InventoryBalance result = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(0, result.getReservedQuantity());

        assertFalse(
                movementRepository.existsByIdempotencyKey("ROLLBACK-TEST-001")
        );
    }

    @Test
    void sellingPriceOverrideMustBePositiveWhenProvided() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            variant.setSellingPriceOverride(new BigDecimal("100.00"));
            assertTrue(validator.validate(variant).isEmpty());

            variant.setSellingPriceOverride(BigDecimal.ZERO);
            assertFalse(validator.validate(variant).isEmpty());

            variant.setSellingPriceOverride(new BigDecimal("-10.00"));
            assertFalse(validator.validate(variant).isEmpty());

            variant.setSellingPriceOverride(null);
            assertTrue(validator.validate(variant).isEmpty());
        }
    }

    private ProductVariant createVariant(String sku, String barcode) {
        ProductVariant result = new ProductVariant();
        result.setProduct(product);
        result.setSku(sku);
        result.setBarcode(barcode);
        result.setVariantName("Duplicate Test Variant");
        result.setAttributes(Map.of("color", "Black"));
        return result;
    }

    private InventoryBalance createBalance() {
        InventoryBalance result = new InventoryBalance();
        result.setLocation(location);
        result.setVariant(variant);
        result.setOnHandQuantity(10);
        result.setReservedQuantity(0);
        result.setReorderLevel(2);
        return result;
    }

    private StockMovement createMovement(String idempotencyKey) {
        StockMovement result = new StockMovement();
        result.setLocation(location);
        result.setVariant(variant);
        result.setMovementType("RECEIPT");
        result.setQuantityDelta(10);
        result.setReservedDelta(0);
        result.setOnHandAfter(10);
        result.setReservedAfter(0);
        result.setIdempotencyKey(idempotencyKey);
        result.setReason("Inventory repository test");
        return result;
    }
}
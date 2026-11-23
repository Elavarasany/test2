package nmk_backend.repository;

import nmk_backend.entity.*;
import nmk_backend.service.InventoryService;
import nmk_backend.service.ProductService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
@Transactional
class InventoryMySqlIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository variantRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductService productService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void deletingProductDeactivatesItAndPreservesInventoryAndMovementHistory() {
        Product product = new Product();
        product.setName("Soft Delete Integration Product");
        product.setPrice(new BigDecimal("1499.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("SOFT-DELETE-" + UUID.randomUUID());
        variant.setVariantName("Preserved Variant");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variantRepository.saveAndFlush(variant);

        InventoryLocation location = new InventoryLocation();
        location.setCode("SD-" + UUID.randomUUID());
        location.setName("Soft Delete Test Location");
        location.setActive(true);
        entityManager.persist(location);
        entityManager.flush();

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(3);
        balance.setReservedQuantity(0);
        balance.setReorderLevel(0);
        balanceRepository.saveAndFlush(balance);

        Long productId = product.getId();
        Long variantId = variant.getId();
        Long locationId = location.getId();

        inventoryService.receiveStock(
                locationId, variantId, 1,
                "SOFT-DELETE-TEST-" + UUID.randomUUID(),
                null, "Audit movement before product deactivation"
        );

        productService.deleteProduct(productId);
        entityManager.flush();
        entityManager.clear();

        Product persistedProduct = productRepository.findById(productId).orElseThrow();
        ProductVariant persistedVariant = variantRepository.findById(variantId).orElseThrow();
        InventoryBalance persistedBalance = balanceRepository
                .findByLocationIdAndVariantId(locationId, variantId)
                .orElseThrow();

        assertFalse(persistedProduct.isActive());
        assertTrue(persistedVariant.isActive());
        assertEquals(4, persistedBalance.getOnHandQuantity());
        assertEquals(1, movementRepository.findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                locationId, variantId
        ).size());
    }

    @Test
    void stockMovementSnapshotsReconcileAcrossReceiptReservationSaleReturnsAndCountVariance() {
        Product product = new Product();
        product.setName("Stock Ledger Reconciliation Product");
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("LEDGER-" + UUID.randomUUID());
        variant.setVariantName("Ledger Test Variant");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variantRepository.saveAndFlush(variant);

        InventoryLocation location = new InventoryLocation();
        location.setCode("LD-" + UUID.randomUUID());
        location.setName("Stock Ledger Test Location");
        location.setActive(true);
        entityManager.persist(location);
        entityManager.flush();

        Long locationId = location.getId();
        Long variantId = variant.getId();

        inventoryService.receiveStock(locationId, variantId, 10,
                "LEDGER-RECEIPT-" + UUID.randomUUID(), null, "Opening count receipt");
        inventoryService.reserveStock(locationId, variantId, 3,
                "LEDGER-RESERVE-" + UUID.randomUUID(), null, "Online order reservation");
        inventoryService.consumeReservation(locationId, variantId, 1,
                "LEDGER-CONSUME-" + UUID.randomUUID(), null, "Captured online order");
        inventoryService.sellStock(locationId, variantId, 2,
                "LEDGER-SALE-" + UUID.randomUUID(), null, "Walk-in POS sale", "POS_SALE", 7001L);
        inventoryService.restockCustomerReturn(locationId, variantId, 1,
                "LEDGER-RETURN-" + UUID.randomUUID(), null, "Inspected resaleable return", 7002L);
        inventoryService.returnStockToSupplier(locationId, variantId, 1,
                "LEDGER-SUPPLIER-RETURN-" + UUID.randomUUID(), null, "Supplier quality return", 7003L);
        inventoryService.applyStockCountVariance(locationId, variantId, -1,
                "LEDGER-COUNT-" + UUID.randomUUID(), null, "Physical count variance", 7004L);

        entityManager.flush();
        entityManager.clear();

        InventoryBalance balance = balanceRepository.findByLocationIdAndVariantId(locationId, variantId)
                .orElseThrow();
        assertEquals(6, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());

        var movements = movementRepository.findByLocationIdAndVariantIdOrderByCreatedAtDesc(locationId, variantId)
                .stream()
                .sorted(java.util.Comparator.comparing(StockMovement::getId))
                .toList();

        assertEquals(7, movements.size());
        int expectedOnHand = 0;
        int expectedReserved = 0;
        for (StockMovement movement : movements) {
            expectedOnHand += movement.getQuantityDelta();
            expectedReserved += movement.getReservedDelta();
            assertEquals(expectedOnHand, movement.getOnHandAfter(),
                    "Each movement snapshot must equal the cumulative on-hand ledger");
            assertEquals(expectedReserved, movement.getReservedAfter(),
                    "Each movement snapshot must equal the cumulative reservation ledger");
        }
        assertEquals(balance.getOnHandQuantity(), expectedOnHand);
        assertEquals(balance.getReservedQuantity(), expectedReserved);
    }

    @Test
    void mysqlPersistsVariantJsonAttributesAndSellingPriceOverride() {
        Product product = new Product();
        product.setName("MySQL Integration Test Product");
        product.setPrice(new BigDecimal("999.00"));
        product.setStockQuantity(0);
        product.setActive(true);

        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("MYSQL-JSON-SKU-" + UUID.randomUUID());
        variant.setVariantName("Black Rectangle Frame");
        variant.setAttributes(Map.of(
                "color", "Black",
                "shape", "Rectangle",
                "material", "TR90"
        ));
        variant.setSellingPriceOverride(new BigDecimal("1199.00"));
        variant.setActive(true);

        variantRepository.saveAndFlush(variant);

        entityManager.clear();

        ProductVariant result = variantRepository
                .findBySku(variant.getSku())
                .orElseThrow();

        assertEquals("Black Rectangle Frame", result.getVariantName());
        assertEquals("Black", result.getAttributes().get("color"));
        assertEquals("Rectangle", result.getAttributes().get("shape"));
        assertEquals("TR90", result.getAttributes().get("material"));
        assertEquals(new BigDecimal("1199.00"), result.getSellingPriceOverride());
    }
}

package nmk_backend.service;

import nmk_backend.dto.StockCountLineRequest;
import nmk_backend.dto.StockCountStartRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class StockCountMySqlIntegrationTest {

    @Autowired private StockCountService stockCounts;
    @Autowired private InventoryService inventory;
    @Autowired private AdminRepository admins;
    @Autowired private InventoryLocationRepository locations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryBalanceRepository balances;
    @Autowired private StockMovementRepository movements;

    @Test
    void postingAndRetryingStockCountAppliesEachVarianceExactlyOnceOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String counter = "mysql-count-counter-" + suffix;
        String reviewer = "mysql-count-reviewer-" + suffix;

        Admin counterAccount = new Admin();
        counterAccount.setUsername(counter);
        counterAccount.setPassword("test-only-password");
        admins.saveAndFlush(counterAccount);

        Admin reviewerAccount = new Admin();
        reviewerAccount.setUsername(reviewer);
        reviewerAccount.setPassword("test-only-password");
        admins.saveAndFlush(reviewerAccount);

        InventoryLocation location = new InventoryLocation();
        location.setCode("SC-" + suffix);
        location.setName("MySQL Stock Count " + suffix);
        location.setActive(true);
        location = locations.saveAndFlush(location);

        Product product = new Product();
        product.setName("MySQL Count Frame " + suffix);
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = products.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("SC-" + suffix);
        variant.setVariantName("Count Test Variant");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variants.saveAndFlush(variant);

        inventory.receiveStock(location.getId(), variant.getId(), 4,
                "MYSQL-STOCK-COUNT-OPENING-" + suffix, null, "Stock count integration fixture");

        var started = stockCounts.start(
                new StockCountStartRequest(location.getId(), "MySQL stock count posting integration test"),
                counter
        );
        assertEquals("COUNTING", started.status());
        assertTrue(locations.findById(location.getId()).orElseThrow().isStockCountLocked());

        for (var line : started.lines()) {
            int physicalQuantity = line.variantId().equals(variant.getId())
                    ? 3 : line.snapshotOnHand();
            stockCounts.recordCount(started.id(), line.id(),
                    new StockCountLineRequest(physicalQuantity), counter);
        }

        var submitted = stockCounts.submit(started.id(), counter);
        assertEquals("READY_FOR_REVIEW", submitted.status());
        assertThrows(IllegalArgumentException.class, () -> stockCounts.post(started.id(), counter),
                "The counter must not approve their own physical count");

        var posted = stockCounts.post(started.id(), reviewer);
        assertEquals("POSTED", posted.status());
        assertEquals(reviewer, posted.postedByUsername());
        assertFalse(locations.findById(location.getId()).orElseThrow().isStockCountLocked());

        InventoryBalance finalBalance = balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        assertEquals(3, finalBalance.getOnHandQuantity());
        String varianceKey = "STOCK-COUNT:" + started.id() + ":" + variant.getId();
        assertTrue(movements.existsByIdempotencyKey(varianceKey));
        long movementsAfterFirstPost = movements.count();

        var replay = stockCounts.post(started.id(), reviewer);
        assertEquals("POSTED", replay.status());
        assertEquals(movementsAfterFirstPost, movements.count(),
                "A repeated post must not create additional stock movements");
        assertEquals(3, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity());
    }

    @Test
    void failedMultiLineStockCountPostRollsBackEarlierVariancesAndKeepsLocationFrozen() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String counter = "mysql-count-rollback-counter-" + suffix;
        String reviewer = "mysql-count-rollback-reviewer-" + suffix;

        Admin counterAccount = new Admin();
        counterAccount.setUsername(counter);
        counterAccount.setPassword("test-only-password");
        admins.saveAndFlush(counterAccount);

        Admin reviewerAccount = new Admin();
        reviewerAccount.setUsername(reviewer);
        reviewerAccount.setPassword("test-only-password");
        admins.saveAndFlush(reviewerAccount);

        InventoryLocation location = new InventoryLocation();
        location.setCode("SC-RB-" + suffix);
        location.setName("MySQL Stock Count Rollback " + suffix);
        location.setActive(true);
        location = locations.saveAndFlush(location);

        Product firstProduct = new Product();
        firstProduct.setName("Count Rollback First " + suffix);
        firstProduct.setPrice(new BigDecimal("100.00"));
        firstProduct.setStockQuantity(0);
        firstProduct.setActive(true);
        firstProduct = products.saveAndFlush(firstProduct);

        ProductVariant firstVariant = new ProductVariant();
        firstVariant.setProduct(firstProduct);
        firstVariant.setSku("SC-RB-A-" + suffix);
        firstVariant.setVariantName("First");
        firstVariant.setAttributes(Map.of("color", "Black"));
        firstVariant.setActive(true);
        firstVariant = variants.saveAndFlush(firstVariant);

        Product secondProduct = new Product();
        secondProduct.setName("Count Rollback Second " + suffix);
        secondProduct.setPrice(new BigDecimal("100.00"));
        secondProduct.setStockQuantity(0);
        secondProduct.setActive(true);
        secondProduct = products.saveAndFlush(secondProduct);

        ProductVariant secondVariant = new ProductVariant();
        secondVariant.setProduct(secondProduct);
        secondVariant.setSku("SC-RB-B-" + suffix);
        secondVariant.setVariantName("Second");
        secondVariant.setAttributes(Map.of("color", "Brown"));
        secondVariant.setActive(true);
        secondVariant = variants.saveAndFlush(secondVariant);

        inventory.receiveStock(location.getId(), firstVariant.getId(), 4,
                "MYSQL-COUNT-RB-RECEIVE-A-" + suffix, null, "Rollback fixture first stock");
        inventory.receiveStock(location.getId(), secondVariant.getId(), 2,
                "MYSQL-COUNT-RB-RECEIVE-B-" + suffix, null, "Rollback fixture second stock");
        inventory.reserveStock(location.getId(), secondVariant.getId(), 1,
                "MYSQL-COUNT-RB-RESERVE-" + suffix, null, "Reservation must not be counted away");

        var started = stockCounts.start(
                new StockCountStartRequest(location.getId(), "Verify multi-line count rollback"),
                counter
        );
        for (var line : started.lines()) {
            int physicalQuantity = line.variantId().equals(firstVariant.getId())
                    ? 3
                    : line.variantId().equals(secondVariant.getId())
                        ? 0
                        : line.snapshotOnHand();
            stockCounts.recordCount(started.id(), line.id(),
                    new StockCountLineRequest(physicalQuantity), counter);
        }
        stockCounts.submit(started.id(), counter);

        long movementCountBeforePost = movements.count();
        assertThrows(nmk_backend.exception.InvalidInventoryOperationException.class,
                () -> stockCounts.post(started.id(), reviewer),
                "The second line cannot reduce on-hand below its reserved quantity");

        assertEquals("READY_FOR_REVIEW", stockCounts.getSession(started.id()).status());
        assertTrue(locations.findById(location.getId()).orElseThrow().isStockCountLocked(),
                "A failed post must leave the location frozen for safe correction");
        assertEquals(4, balances.findByLocationIdAndVariantId(location.getId(), firstVariant.getId())
                .orElseThrow().getOnHandQuantity(),
                "The earlier successful variance must roll back with the failed second line");
        InventoryBalance secondBalance = balances.findByLocationIdAndVariantId(
                location.getId(), secondVariant.getId()
        ).orElseThrow();
        assertEquals(2, secondBalance.getOnHandQuantity());
        assertEquals(1, secondBalance.getReservedQuantity());
        assertEquals(movementCountBeforePost, movements.count(),
                "No partial stock-count movements may survive a failed post");
    }
}

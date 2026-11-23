package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.exception.InsufficientStockException;
import nmk_backend.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class InventoryMySqlConcurrencyTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record InventoryIds(
            Long locationId,
            Long variantId,
            Long productId
    ) {}

    @Test
    void concurrentDuplicateRequestsCreateOnlyOneMovement() throws Exception {
        InventoryIds ids = createInventory(0);

        String key = "CONCURRENT-" + UUID.randomUUID();

        List<Boolean> results = runConcurrently(
                () -> {
                    inventoryService.receiveStock(
                            ids.locationId(), ids.variantId(),
                            5, key, null, "Concurrent duplicate test"
                    );
                    return true;
                },
                () -> {
                    inventoryService.receiveStock(
                            ids.locationId(), ids.variantId(),
                            5, key, null, "Concurrent duplicate test"
                    );
                    return true;
                }
        );

        assertEquals(List.of(true, true), results);

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(
                            ids.locationId(), ids.variantId()
                    )
                    .orElseThrow();

            assertEquals(5, balance.getOnHandQuantity());
            assertEquals(0, balance.getReservedQuantity());

            List<StockMovement> movements =
                    movementRepository
                            .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                                    ids.locationId(), ids.variantId()
                            );

            assertEquals(1, movements.size());
            assertEquals(key, movements.get(0).getIdempotencyKey());

            return null;
        });
    }

    @Test
    void concurrentFirstReceiptsCreateOneBalanceAndPreserveBothMovements() throws Exception {
        InventoryIds ids = createInventory(0);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(ids.locationId(), ids.variantId())
                    .orElseThrow();
            balanceRepository.delete(balance);
            balanceRepository.flush();
            return null;
        });

        String key1 = "FIRST-RECEIPT-A-" + UUID.randomUUID();
        String key2 = "FIRST-RECEIPT-B-" + UUID.randomUUID();

        List<Boolean> results = runConcurrently(
                () -> {
                    inventoryService.receiveStock(
                            ids.locationId(), ids.variantId(), 3,
                            key1, null, "Concurrent first receipt A"
                    );
                    return true;
                },
                () -> {
                    inventoryService.receiveStock(
                            ids.locationId(), ids.variantId(), 4,
                            key2, null, "Concurrent first receipt B"
                    );
                    return true;
                }
        );

        assertEquals(List.of(true, true), results);
        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(ids.locationId(), ids.variantId())
                    .orElseThrow();
            assertEquals(7, balance.getOnHandQuantity());
            assertEquals(0, balance.getReservedQuantity());

            List<StockMovement> movements = movementRepository
                    .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                            ids.locationId(), ids.variantId()
                    );
            assertEquals(2, movements.size());
            assertTrue(movements.stream().anyMatch(m -> key1.equals(m.getIdempotencyKey())));
            assertTrue(movements.stream().anyMatch(m -> key2.equals(m.getIdempotencyKey())));
            assertEquals(1, balanceRepository.findByLocationId(ids.locationId()).stream()
                    .filter(row -> row.getVariant().getId().equals(ids.variantId()))
                    .count());
            return null;
        });
    }

    @Test
    void concurrentReservationsCannotOversellStock() throws Exception {
        InventoryIds ids = createInventory(5);

        String key1 = "RESERVE-A-" + UUID.randomUUID();
        String key2 = "RESERVE-B-" + UUID.randomUUID();

        List<Boolean> results = runConcurrently(
                () -> reserveAndReport(
                        ids, key1
                ),
                () -> reserveAndReport(
                        ids, key2
                )
        );

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, results.stream().filter(result -> !result).count());

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(
                            ids.locationId(), ids.variantId()
                    )
                    .orElseThrow();

            assertEquals(5, balance.getOnHandQuantity());
            assertEquals(4, balance.getReservedQuantity());
            assertEquals(1, balance.getAvailableQuantity());

            List<StockMovement> movements =
                    movementRepository
                            .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                                    ids.locationId(), ids.variantId()
                            );

            assertEquals(1, movements.size());
            assertEquals("RESERVATION", movements.get(0).getMovementType());

            return null;
        });
    }

    @Test
    void concurrentOnlineReservationAndPosSaleCannotConsumeTheSameLastUnit() throws Exception {
        InventoryIds ids = createInventory(1);

        String reservationKey = "ONLINE-VS-POS-RESERVE-" + UUID.randomUUID();
        String saleKey = "ONLINE-VS-POS-SALE-" + UUID.randomUUID();

        List<Boolean> outcomes = runConcurrently(
                () -> attemptOnlineReservation(ids, reservationKey),
                () -> attemptPosSale(ids, saleKey)
        );

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count(),
                "Exactly one channel may claim the only available unit");
        assertEquals(1, outcomes.stream().filter(result -> !result).count(),
                "The competing channel must be rejected for insufficient stock");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(ids.locationId(), ids.variantId())
                    .orElseThrow();

            assertTrue(
                    (balance.getOnHandQuantity() == 1 && balance.getReservedQuantity() == 1)
                            || (balance.getOnHandQuantity() == 0 && balance.getReservedQuantity() == 0),
                    "Final inventory must reflect exactly one successful channel operation"
            );

            List<StockMovement> movements = movementRepository
                    .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                            ids.locationId(), ids.variantId()
                    );
            assertEquals(1, movements.size(),
                    "Only the winning channel may create a stock movement");
            assertTrue(
                    "RESERVATION".equals(movements.get(0).getMovementType())
                            || "SALE".equals(movements.get(0).getMovementType()),
                    "Movement must belong to the winning online reservation or POS sale"
            );
            return null;
        });
    }

    private boolean attemptOnlineReservation(InventoryIds ids, String key) {
        try {
            inventoryService.reserveStock(
                    ids.locationId(), ids.variantId(), 1, key, null,
                    "Concurrent online checkout reservation"
            );
            return true;
        } catch (InsufficientStockException exception) {
            return false;
        }
    }

    private boolean attemptPosSale(InventoryIds ids, String key) {
        try {
            inventoryService.sellStock(
                    ids.locationId(), ids.variantId(), 1, key, null,
                    "Concurrent in-store POS sale", "POS_SALE", 123L
            );
            return true;
        } catch (InsufficientStockException exception) {
            return false;
        }
    }

    @Test
    void reservationCanBeReleasedAfterProductIsDeactivated() {
        InventoryIds ids = createInventory(5);

        String reserveKey = "MYSQL-RESERVE-" + UUID.randomUUID();
        String releaseKey = "MYSQL-RELEASE-" + UUID.randomUUID();

        inventoryService.reserveStock(
                ids.locationId(),
                ids.variantId(),
                3,
                reserveKey,
                null,
                "Reserve before product deactivation"
        );

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            Product product = productRepository
                    .findById(ids.productId())
                    .orElseThrow();

            product.setActive(false);
            productRepository.saveAndFlush(product);
            return null;
        });

        assertDoesNotThrow(() ->
                inventoryService.releaseReservation(
                        ids.locationId(),
                        ids.variantId(),
                        3,
                        releaseKey,
                        null,
                        "Release after product deactivation"
                )
        );

        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(
                            ids.locationId(), ids.variantId()
                    )
                    .orElseThrow();

            assertEquals(5, balance.getOnHandQuantity());
            assertEquals(0, balance.getReservedQuantity());
            assertEquals(5, balance.getAvailableQuantity());

            List<StockMovement> movements =
                    movementRepository
                            .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                                    ids.locationId(), ids.variantId()
                            );

            assertEquals(2, movements.size());
            assertTrue(movements.stream().anyMatch(
                    movement -> "RESERVATION".equals(
                            movement.getMovementType()
                    )
            ));
            assertTrue(movements.stream().anyMatch(
                    movement -> "RELEASE".equals(
                            movement.getMovementType()
                    )
            ));

            return null;
        });
    }

    @Test
    void reservationCanBeConsumedAfterVariantIsDeactivated() {
        InventoryIds ids = createInventory(5);

        String reserveKey = "MYSQL-RESERVE-" + UUID.randomUUID();
        String consumeKey = "MYSQL-CONSUME-" + UUID.randomUUID();

        inventoryService.reserveStock(
                ids.locationId(),
                ids.variantId(),
                3,
                reserveKey,
                null,
                "Reserve before variant deactivation"
        );

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            ProductVariant variant = variantRepository
                    .findById(ids.variantId())
                    .orElseThrow();

            variant.setActive(false);
            variantRepository.saveAndFlush(variant);
            return null;
        });

        assertDoesNotThrow(() ->
                inventoryService.consumeReservation(
                        ids.locationId(),
                        ids.variantId(),
                        3,
                        consumeKey,
                        null,
                        "Consume after variant deactivation"
                )
        );

        transaction.execute(status -> {
            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(
                            ids.locationId(), ids.variantId()
                    )
                    .orElseThrow();

            assertEquals(2, balance.getOnHandQuantity());
            assertEquals(0, balance.getReservedQuantity());
            assertEquals(2, balance.getAvailableQuantity());

            List<StockMovement> movements =
                    movementRepository
                            .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                                    ids.locationId(), ids.variantId()
                            );

            assertEquals(2, movements.size());
            assertTrue(movements.stream().anyMatch(
                    movement -> "RESERVATION".equals(
                            movement.getMovementType()
                    )
            ));
            assertTrue(movements.stream().anyMatch(
                    movement -> "CONSUMPTION".equals(
                            movement.getMovementType()
                    )
            ));

            return null;
        });
    }


    private boolean reserveAndReport(
            InventoryIds ids,
            String key
    ) {
        try {
            inventoryService.reserveStock(
                    ids.locationId(),
                    ids.variantId(),
                    4,
                    key,
                    null,
                    "Concurrent overselling test"
            );
            return true;
        } catch (InsufficientStockException exception) {
            return false;
        }
    }

    private InventoryIds createInventory(int initialStock) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        return transaction.execute(status -> {
            String unique = UUID.randomUUID().toString();

            Product product = new Product();
            product.setName("Concurrency Test " + unique);
            product.setPrice(new BigDecimal("999.00"));
            product.setStockQuantity(0);
            product.setActive(true);
            product = productRepository.saveAndFlush(product);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(product);
            variant.setSku("CONC-" + unique);
            variant.setVariantName("Concurrency Test Variant");
            variant.setAttributes(java.util.Map.of("color", "Black"));
            variant.setActive(true);
            variant = variantRepository.saveAndFlush(variant);

            InventoryLocation location = new InventoryLocation();
            location.setCode("C-" + unique);
            location.setName("Concurrency Test Location");
            location.setActive(true);
            location = locationRepository.saveAndFlush(location);

            InventoryBalance balance = new InventoryBalance();
            balance.setLocation(location);
            balance.setVariant(variant);
            balance.setOnHandQuantity(initialStock);
            balance.setReservedQuantity(0);
            balance.setReorderLevel(0);
            balanceRepository.saveAndFlush(balance);

            return new InventoryIds(
                    location.getId(),
                    variant.getId(),
                    product.getId()
            );
        });
    }

    private <T> List<T> runConcurrently(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<T> firstResult = executor.submit(() -> {
                ready.countDown();

                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Start signal timed out");
                }

                return first.call();
            });

            Future<T> secondResult = executor.submit(() -> {
                ready.countDown();

                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Start signal timed out");
                }

                return second.call();
            });

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Worker threads did not become ready"
            );

            start.countDown();

            return List.of(
                    firstResult.get(30, TimeUnit.SECONDS),
                    secondResult.get(30, TimeUnit.SECONDS)
            );

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
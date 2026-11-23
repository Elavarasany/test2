package nmk_backend.service;

import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class PosReturnMySqlConcurrencyTest {

    @Autowired private PosCheckoutService checkoutService;
    @Autowired private PosReturnService returnService;
    @Autowired private PosShiftService shiftService;
    @Autowired private InventoryService inventoryService;
    @Autowired private AdminRepository adminRepository;
    @Autowired private InventoryLocationRepository locationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryBalanceRepository balanceRepository;
    @Autowired private StockMovementRepository movementRepository;
    @Autowired private PosSaleItemRepository saleItemRepository;
    @Autowired private PosReturnRepository returnRepository;
    @Autowired private PosReturnItemRepository returnItemRepository;

    private record Fixture(Long locationId, Long variantId, Long productId, Long shiftId, String username) {}
    private record Outcome(boolean successful, Throwable failure) {}

    @Test
    void concurrentReturnsCannotReturnTheSameLastUnitTwiceOnMySql() throws Exception {
        Fixture fixture = createFixture();
        inventoryService.receiveStock(
                fixture.locationId(), fixture.variantId(), 1,
                "MYSQL-RETURN-OPENING-" + UUID.randomUUID(), null,
                "Opening unit for concurrent return integration test"
        );

        PosCheckoutRequest checkout = new PosCheckoutRequest();
        checkout.setLocationId(fixture.locationId());
        checkout.setShiftId(fixture.shiftId());
        checkout.setIdempotencyKey("MYSQL-RETURN-SALE-" + UUID.randomUUID());
        checkout.setDiscountAmount(new BigDecimal("0.00"));

        PosSaleItemRequest saleItem = new PosSaleItemRequest();
        saleItem.setProductId(fixture.productId());
        saleItem.setVariantId(fixture.variantId());
        saleItem.setQuantity(1);
        checkout.setItems(List.of(saleItem));

        PosPaymentRequest payment = new PosPaymentRequest();
        payment.setPaymentMethod("CASH");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setTenderedAmount(new BigDecimal("100.00"));
        checkout.setPayments(List.of(payment));

        PosSaleResponse sale = checkoutService.checkout(checkout, fixture.username());
        Long saleItemId = saleItemRepository.findBySaleId(sale.id())
                .stream().findFirst().orElseThrow().getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> returnOne(
                    fixture, sale.id(), saleItemId, ready, start));
            Future<Outcome> second = executor.submit(() -> returnOne(
                    fixture, sale.id(), saleItemId, ready, start));

            assertTrue(ready.await(15, TimeUnit.SECONDS), "Both MySQL return workers should be ready");
            start.countDown();

            Outcome firstOutcome = first.get(30, TimeUnit.SECONDS);
            Outcome secondOutcome = second.get(30, TimeUnit.SECONDS);
            long successful = List.of(firstOutcome, secondOutcome).stream()
                    .filter(Outcome::successful).count();
            long rejectedForQuantity = List.of(firstOutcome, secondOutcome).stream()
                    .filter(outcome -> outcome.failure() instanceof IllegalArgumentException).count();

            assertEquals(1, successful,
                    "Only one concurrent return may claim the single sold unit");
            assertEquals(1, rejectedForQuantity,
                    "The competing return must see the committed return quantity");
            assertEquals(1, returnRepository.findBySaleIdOrderByCreatedAtDesc(sale.id()).size());
            assertEquals(1L, returnItemRepository.sumReturnedQuantityBySaleItemId(saleItemId),
                    "The sale line should have exactly one returned unit, independent of other integration fixtures");

            InventoryBalance balance = balanceRepository
                    .findByLocationIdAndVariantId(fixture.locationId(), fixture.variantId())
                    .orElseThrow();
            assertEquals(1, balance.getOnHandQuantity(),
                    "The returned unit must be restocked exactly once");
            assertEquals(0, balance.getReservedQuantity());

            long movementCount = movementRepository
                    .findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                            fixture.locationId(), fixture.variantId())
                    .size();
            assertEquals(3, movementCount,
                    "One opening receipt, one sale and one successful return must be recorded");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void shiftCloseAndCashRefundSerializeOnMySql() throws Exception {
        Fixture fixture = createFixture();
        inventoryService.receiveStock(
                fixture.locationId(), fixture.variantId(), 1,
                "MYSQL-CLOSE-REFUND-OPENING-" + UUID.randomUUID(), null,
                "Opening unit for close/refund race test"
        );

        PosCheckoutRequest checkout = new PosCheckoutRequest();
        checkout.setLocationId(fixture.locationId());
        checkout.setShiftId(fixture.shiftId());
        checkout.setIdempotencyKey("MYSQL-CLOSE-REFUND-SALE-" + UUID.randomUUID());
        checkout.setDiscountAmount(new BigDecimal("0.00"));
        PosSaleItemRequest saleItem = new PosSaleItemRequest();
        saleItem.setProductId(fixture.productId());
        saleItem.setVariantId(fixture.variantId());
        saleItem.setQuantity(1);
        checkout.setItems(List.of(saleItem));
        PosPaymentRequest payment = new PosPaymentRequest();
        payment.setPaymentMethod("CASH");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setTenderedAmount(new BigDecimal("100.00"));
        checkout.setPayments(List.of(payment));

        PosSaleResponse sale = checkoutService.checkout(checkout, fixture.username());
        Long saleItemId = saleItemRepository.findBySaleId(sale.id()).get(0).getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> refundFuture = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        return new Outcome(false, new TimeoutException("Race start timed out"));
                    }
                    PosReturnRequest request = new PosReturnRequest(
                            fixture.shiftId(), "Close/refund serialization test",
                            "MYSQL-CLOSE-REFUND-KEY-" + UUID.randomUUID(),
                            List.of(new PosReturnItemRequest(saleItemId, 1, "RESTOCK", null)),
                            List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("100.00"), null))
                    );
                    returnService.processReturn(sale.id(), request, fixture.username());
                    return new Outcome(true, null);
                } catch (Throwable failure) {
                    return new Outcome(false, failure);
                }
            });
            Future<?> closeFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Race start timed out");
                }
                return shiftService.closeShift(
                        fixture.shiftId(),
                        new PosShiftCloseRequest(new BigDecimal("100.00"),
                                "Count fixed for close/refund concurrency test"),
                        fixture.username(), "CASHIER"
                );
            });

            assertTrue(ready.await(15, TimeUnit.SECONDS), "Both MySQL workers should be ready");
            start.countDown();
            Outcome refund = refundFuture.get(30, TimeUnit.SECONDS);
            var closedShift = (nmk_backend.dto.PosShiftResponse) closeFuture.get(30, TimeUnit.SECONDS);

            assertEquals("CLOSED", closedShift.status());
            if (refund.successful()) {
                assertEquals(new BigDecimal("100.00"), closedShift.expectedCash(),
                        "Refund must be included before the shift is closed");
                assertEquals(1, returnRepository.findBySaleIdOrderByCreatedAtDesc(sale.id()).size());
                assertEquals(new BigDecimal("0.00"), closedShift.cashVariance());
            } else {
                assertTrue(refund.failure() instanceof IllegalArgumentException,
                        "A refund arriving after close must be rejected, not fail unexpectedly");
                assertEquals(new BigDecimal("200.00"), closedShift.expectedCash(),
                        "A close that wins the lock must preserve pre-refund drawer cash");
                assertEquals(0, returnRepository.findBySaleIdOrderByCreatedAtDesc(sale.id()).size());
                assertEquals(new BigDecimal("-100.00"), closedShift.cashVariance());
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Outcome returnOne(
            Fixture fixture, Long saleId, Long saleItemId,
            CountDownLatch ready, CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(15, TimeUnit.SECONDS)) {
                return new Outcome(false, new TimeoutException("Return start signal timed out"));
            }
            PosReturnRequest request = new PosReturnRequest(
                    fixture.shiftId(),
                    "Concurrent MySQL return quantity test",
                    "MYSQL-RETURN-KEY-" + UUID.randomUUID(),
                    List.of(new PosReturnItemRequest(saleItemId, 1, "RESTOCK", null)),
                    List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("100.00"), null))
            );
            returnService.processReturn(saleId, request, fixture.username());
            return new Outcome(true, null);
        } catch (Throwable failure) {
            return new Outcome(false, failure);
        }
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "mysql-return-" + suffix;

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword("test-only-password");
        adminRepository.saveAndFlush(admin);

        InventoryLocation location = new InventoryLocation();
        location.setCode("RET-" + suffix);
        location.setName("MySQL Return Test " + suffix);
        location.setActive(true);
        location = locationRepository.saveAndFlush(location);
        admin.setAssignedLocations(java.util.Set.of(location));
        adminRepository.saveAndFlush(admin);

        PosRegisterResponse register = shiftService.createRegister(
                new PosRegisterCreateRequest(location.getId(), "RET-" + suffix, "Return Test Register")
        );
        Long shiftId = shiftService.openShift(
                new PosShiftOpenRequest(register.id(), new BigDecimal("100.00"),
                        "MYSQL-RETURN-SHIFT-" + suffix),
                username
        ).id();

        Product product = new Product();
        product.setName("MySQL return concurrency " + suffix);
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("RET-" + suffix);
        variant.setVariantName("Test variant");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variantRepository.saveAndFlush(variant);

        return new Fixture(location.getId(), variant.getId(), product.getId(), shiftId, username);
    }
}

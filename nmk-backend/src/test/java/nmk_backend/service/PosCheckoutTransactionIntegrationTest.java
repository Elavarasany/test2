package nmk_backend.service;

import nmk_backend.dto.PosCheckoutRequest;
import nmk_backend.dto.PosPaymentRequest;
import nmk_backend.dto.PosSaleItemRequest;
import nmk_backend.dto.PosReturnItemRequest;
import nmk_backend.dto.PosReturnRequest;
import nmk_backend.dto.PosRefundPaymentRequest;
import nmk_backend.dto.PosExchangeRequest;
import nmk_backend.repository.PosExchangeRepository;
import nmk_backend.repository.PosExchangeCreditSettlementRepository;
import nmk_backend.repository.PosPaymentRepository;
import nmk_backend.repository.PosRefundPaymentRepository;
import nmk_backend.repository.PosReturnItemRepository;
import nmk_backend.repository.PosReturnRepository;
import nmk_backend.dto.PosRegisterCreateRequest;
import nmk_backend.dto.PosRegisterResponse;
import nmk_backend.dto.PosShiftOpenRequest;
import nmk_backend.entity.Admin;
import nmk_backend.entity.InventoryBalance;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.repository.PosRegisterRepository;
import nmk_backend.repository.PosShiftRepository;
import nmk_backend.repository.PosCashMovementRepository;
import nmk_backend.exception.InsufficientStockException;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.InventoryBalanceRepository;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.PosSaleRepository;
import nmk_backend.repository.PosSaleItemRepository;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import nmk_backend.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PosCheckoutTransactionIntegrationTest {

    @Autowired
    private PosCheckoutService checkoutService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private InventoryLocationRepository locationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository variantRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private PosSaleRepository saleRepository;

    @Autowired
    private PosSaleItemRepository saleItemRepository;

    @Autowired
    private PosReturnService returnService;

    @Autowired
    private PosExchangeService exchangeService;

    @Autowired
    private PosExchangeRepository exchangeRepository;

    @Autowired
    private PosExchangeCreditSettlementRepository exchangeCreditRepository;

    @Autowired
    private PosPaymentRepository posPaymentRepository;

    @Autowired
    private PosRefundPaymentRepository refundPaymentRepository;

    @Autowired
    private PosReturnRepository returnRepository;

    @Autowired
    private PosReturnItemRepository returnItemRepository;

    @Autowired
    private PosRegisterRepository registerRepository;

    @Autowired
    private PosShiftRepository shiftRepository;

    @Autowired
    private PosCashMovementRepository cashMovementRepository;

    @Autowired
    private PosShiftService shiftService;

    @Autowired
    private PosReportService reportService;

    private Long shiftId;
    private InventoryLocation location;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        cashMovementRepository.deleteAll();
        exchangeCreditRepository.deleteAll();
        exchangeRepository.deleteAll();
        returnItemRepository.deleteAll();
        returnRepository.deleteAll();
        saleRepository.deleteAll();
        shiftRepository.deleteAll();
        registerRepository.deleteAll();
        movementRepository.deleteAll();
        balanceRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        adminRepository.deleteAll();
        locationRepository.deleteAll();

        Admin cashier = new Admin();
        cashier.setUsername("pos-rollback-test-cashier");
        cashier.setPassword("test-only-password");
        adminRepository.saveAndFlush(cashier);

        location = new InventoryLocation();
        location.setCode("POS-ROLLBACK-TEST");
        location.setName("POS Rollback Test Location");
        location.setActive(true);
        location = locationRepository.saveAndFlush(location);
        cashier.setAssignedLocations(java.util.Set.of(location));
        adminRepository.saveAndFlush(cashier);

        PosRegisterResponse register = shiftService.createRegister(
                new PosRegisterCreateRequest(location.getId(), "MAIN", "Main Register")
        );
        shiftId = shiftService.openShift(
                new PosShiftOpenRequest(
                        register.id(),
                        new BigDecimal("100.00"),
                        "POS-TEST-SHIFT-" + System.nanoTime()
                ),
                "pos-rollback-test-cashier"
        ).id();

        product = new Product();
        product.setName("POS Rollback Test Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("POS-ROLLBACK-TEST-SKU");
        variant.setVariantName("Test Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variantRepository.saveAndFlush(variant);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(0);
        balance.setReservedQuantity(0);
        balance.setReorderLevel(0);
        balanceRepository.saveAndFlush(balance);
    }

    @Test
    void equalValueExchangeUsesNonCashCreditAndCommitsReturnReplacementAndStockTogether() {
        InventoryBalance balance = balanceRepository.findByLocationIdAndVariantId(location.getId(), variant.getId()).orElseThrow();
        balance.setOnHandQuantity(2);
        balanceRepository.saveAndFlush(balance);

        var original = checkoutService.checkout(buildCheckoutRequest("POS-EXCHANGE-ORIGINAL-" + System.nanoTime()),
                "pos-rollback-test-cashier");
        Long originalItemId = saleItemRepository.findBySaleId(original.id()).get(0).getId();

        PosSaleItemRequest replacementLine = new PosSaleItemRequest();
        replacementLine.setProductId(product.getId());
        replacementLine.setVariantId(variant.getId());
        replacementLine.setQuantity(1);
        PosExchangeRequest request = new PosExchangeRequest(
                shiftId, "Equal value frame exchange", "POS-EXCHANGE-EQUAL-" + System.nanoTime(),
                List.of(new PosReturnItemRequest(originalItemId, 1, "RESTOCK", null)),
                List.of(replacementLine), new BigDecimal("0.00"), List.of(), List.of());

        var result = exchangeService.exchange(original.id(), request, "pos-rollback-test-cashier", "CASHIER");

        assertEquals(new BigDecimal("100.00"), result.returnedValue());
        assertEquals(new BigDecimal("100.00"), result.replacementValue());
        assertEquals(new BigDecimal("100.00"), result.creditApplied());
        assertEquals(new BigDecimal("0.00"), result.actualRefund());
        assertEquals(new BigDecimal("0.00"), result.amountCollected());
        assertEquals(1, exchangeRepository.count());
        assertEquals(1, exchangeCreditRepository.count());
        assertEquals(1, returnRepository.count());
        assertEquals(2, saleRepository.count(), "Original and replacement sales must both be persisted");
        assertTrue(posPaymentRepository.findBySaleId(result.replacementSaleId()).isEmpty(),
                "Exchange credit must not be represented as a fake tender payment");
        var replay = exchangeService.exchange(original.id(), request, "pos-rollback-test-cashier", "CASHIER");
        assertEquals(result.id(), replay.id(), "An identical exchange retry must replay the original exchange");
        assertEquals(1, exchangeRepository.count(), "Idempotent replay must not duplicate exchange records");

        InventoryBalance finalBalance = balanceRepository.findByLocationIdAndVariantId(location.getId(), variant.getId()).orElseThrow();
        assertEquals(1, finalBalance.getOnHandQuantity(), "Return restock and replacement sale must net to one unit");
        assertEquals(0, finalBalance.getReservedQuantity());
    }

    @Test
    void higherValueExchangeCollectsOnlyTheDifference() {
        InventoryBalance balance = balanceRepository.findByLocationIdAndVariantId(location.getId(), variant.getId()).orElseThrow();
        balance.setOnHandQuantity(2);
        balanceRepository.saveAndFlush(balance);
        var original = checkoutService.checkout(buildCheckoutRequest("POS-EXCHANGE-HIGH-ORIGINAL-" + System.nanoTime()),
                "pos-rollback-test-cashier");
        Long originalItemId = saleItemRepository.findBySaleId(original.id()).get(0).getId();
        product.setPrice(new BigDecimal("150.00"));
        productRepository.saveAndFlush(product);
        PosSaleItemRequest replacementLine = new PosSaleItemRequest();
        replacementLine.setProductId(product.getId()); replacementLine.setVariantId(variant.getId()); replacementLine.setQuantity(1);
        PosPaymentRequest collection = new PosPaymentRequest();
        collection.setPaymentMethod("CASH"); collection.setAmount(new BigDecimal("50.00")); collection.setTenderedAmount(new BigDecimal("50.00"));
        PosExchangeRequest request = new PosExchangeRequest(shiftId,"Higher value replacement","POS-EXCHANGE-HIGH-" + System.nanoTime(),
                List.of(new PosReturnItemRequest(originalItemId,1,"RESTOCK",null)),List.of(replacementLine),
                new BigDecimal("0.00"),List.of(collection),List.of());
        var result = exchangeService.exchange(original.id(),request,"pos-rollback-test-cashier","CASHIER");
        assertEquals(new BigDecimal("100.00"),result.creditApplied());
        assertEquals(new BigDecimal("50.00"),result.amountCollected());
        assertEquals(new BigDecimal("0.00"),result.actualRefund());
        assertEquals(new BigDecimal("50.00"),posPaymentRepository.findBySaleId(result.replacementSaleId()).get(0).getAmount());
    }

    @Test
    void lowerValueExchangeRecordsOnlyTheActualCashRefund() {
        InventoryBalance balance = balanceRepository.findByLocationIdAndVariantId(location.getId(),variant.getId()).orElseThrow();
        balance.setOnHandQuantity(2); balanceRepository.saveAndFlush(balance);
        var original = checkoutService.checkout(buildCheckoutRequest("POS-EXCHANGE-LOW-ORIGINAL-" + System.nanoTime()),
                "pos-rollback-test-cashier");
        Long originalItemId = saleItemRepository.findBySaleId(original.id()).get(0).getId();
        product.setPrice(new BigDecimal("80.00")); productRepository.saveAndFlush(product);
        PosSaleItemRequest replacementLine = new PosSaleItemRequest();
        replacementLine.setProductId(product.getId()); replacementLine.setVariantId(variant.getId()); replacementLine.setQuantity(1);
        PosExchangeRequest request = new PosExchangeRequest(shiftId,"Lower value replacement","POS-EXCHANGE-LOW-" + System.nanoTime(),
                List.of(new PosReturnItemRequest(originalItemId,1,"RESTOCK",null)),List.of(replacementLine),
                new BigDecimal("0.00"),List.of(),List.of(new PosRefundPaymentRequest("CASH",new BigDecimal("20.00"),null)));
        var result = exchangeService.exchange(original.id(),request,"pos-rollback-test-cashier","CASHIER");
        assertEquals(new BigDecimal("80.00"),result.creditApplied());
        assertEquals(new BigDecimal("0.00"),result.amountCollected());
        assertEquals(new BigDecimal("20.00"),result.actualRefund());
        assertEquals(new BigDecimal("20.00"),refundPaymentRepository.findByReturnRecordId(result.returnId()).get(0).getAmount());
    }

    @Test
    void failedReplacementStockConsumptionRollsBackEntireExchange() {
        InventoryBalance balance = balanceRepository.findByLocationIdAndVariantId(location.getId(), variant.getId()).orElseThrow();
        balance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(balance);
        var original = checkoutService.checkout(buildCheckoutRequest("POS-EXCHANGE-ROLLBACK-ORIGINAL-" + System.nanoTime()),
                "pos-rollback-test-cashier");
        Long originalItemId = saleItemRepository.findBySaleId(original.id()).get(0).getId();
        PosSaleItemRequest replacementLine = new PosSaleItemRequest();
        replacementLine.setProductId(product.getId());
        replacementLine.setVariantId(variant.getId());
        replacementLine.setQuantity(1);
        PosExchangeRequest request = new PosExchangeRequest(
                shiftId, "Rollback when replacement stock is unavailable", "POS-EXCHANGE-ROLLBACK-" + System.nanoTime(),
                List.of(new PosReturnItemRequest(originalItemId, 1, "DAMAGED", "Lens damaged")),
                List.of(replacementLine), new BigDecimal("0.00"), List.of(), List.of());

        assertThrows(InsufficientStockException.class,
                () -> exchangeService.exchange(original.id(), request, "pos-rollback-test-cashier", "CASHIER"));
        assertEquals(1, saleRepository.count(), "Failed exchange must not leave a replacement sale");
        assertEquals(0, returnRepository.count(), "Failed exchange must not leave a return");
        assertEquals(0, exchangeRepository.count(), "Failed exchange must not leave an exchange header");
        assertEquals(0, exchangeCreditRepository.count(), "Failed exchange must not leave exchange credit");
        assertEquals(1, movementRepository.count(), "Only the original checkout stock movement should remain");
        InventoryBalance unchanged = balanceRepository.findByLocationIdAndVariantId(location.getId(), variant.getId()).orElseThrow();
        assertEquals(0, unchanged.getOnHandQuantity(), "Failed exchange must not restore damaged goods or consume replacement stock");
    }

    @Test
    void insufficientStockRollsBackSaleAndPaymentRecords() {
        PosCheckoutRequest request = new PosCheckoutRequest();
        request.setLocationId(location.getId());
        request.setShiftId(shiftId);
        request.setIdempotencyKey("POS-ROLLBACK-IT-" + System.nanoTime());
        request.setDiscountAmount(new BigDecimal("0.00"));

        PosSaleItemRequest item = new PosSaleItemRequest();
        item.setProductId(product.getId());
        item.setVariantId(variant.getId());
        item.setQuantity(1);
        request.setItems(List.of(item));

        PosPaymentRequest payment = new PosPaymentRequest();
        payment.setPaymentMethod("CASH");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setTenderedAmount(new BigDecimal("100.00"));
        request.setPayments(List.of(payment));

        assertThrows(
                InsufficientStockException.class,
                () -> checkoutService.checkout(request, "pos-rollback-test-cashier")
        );

        assertEquals(0, saleRepository.count(),
                "The sale and its cascading payment/item records must roll back");
        assertEquals(0, movementRepository.count(),
                "A failed checkout must not leave a stock movement");

        InventoryBalance unchanged = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();

        assertEquals(0, unchanged.getOnHandQuantity());
        assertEquals(0, unchanged.getReservedQuantity());
    }
    @Test
    void onlineReservationAndPosCheckoutCannotConsumeTheSameLastUnit() throws Exception {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(1);
        balance.setReservedQuantity(0);
        balanceRepository.saveAndFlush(balance);

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);

        try {
            var onlineReservation = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        return new CheckoutOutcome(false,
                                new IllegalStateException("Timed out waiting to start online reservation"));
                    }
                    inventoryService.reserveStock(
                            location.getId(),
                            variant.getId(),
                            1,
                            "ONLINE-RESERVATION-CONCURRENT-" + System.nanoTime(),
                            null,
                            "Online checkout stock reservation"
                    );
                    return new CheckoutOutcome(true, null);
                } catch (Throwable failure) {
                    return new CheckoutOutcome(false, failure);
                }
            });
            var posCheckout = executor.submit(() ->
                    performConcurrentCheckout(
                            "POS-VS-ONLINE-CONCURRENT-" + System.nanoTime(), ready, start));

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Online reservation and POS checkout workers should be ready");
            start.countDown();

            CheckoutOutcome reservationOutcome =
                    onlineReservation.get(20, java.util.concurrent.TimeUnit.SECONDS);
            CheckoutOutcome posOutcome =
                    posCheckout.get(20, java.util.concurrent.TimeUnit.SECONDS);

            assertNotEquals(reservationOutcome.successful(), posOutcome.successful(),
                    "Exactly one channel must acquire the final available unit");
            assertEquals(1, movementRepository.count(),
                    "Only the winning reservation or sale may create a stock movement");

            InventoryBalance finalBalance = balanceRepository
                    .findByLocationIdAndVariantId(location.getId(), variant.getId())
                    .orElseThrow();

            if (reservationOutcome.successful()) {
                assertEquals(0, saleRepository.count(),
                        "POS must not sell stock already reserved by online checkout");
                assertEquals(1, finalBalance.getOnHandQuantity());
                assertEquals(1, finalBalance.getReservedQuantity());
                assertEquals(0, finalBalance.getAvailableQuantity());
            } else {
                assertTrue(posOutcome.successful(),
                        "If online reservation loses, POS checkout should be the winner");
                assertEquals(1, saleRepository.count(),
                        "POS sale should persist exactly once when it wins the stock lock");
                assertEquals(0, finalBalance.getOnHandQuantity());
                assertEquals(0, finalBalance.getReservedQuantity());
                assertEquals(0, finalBalance.getAvailableQuantity());
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCheckoutsCannotSellTheSameLastUnitTwice() throws Exception {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(balance);

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);

        try {
            var first = executor.submit(() -> performConcurrentCheckout(
                    "POS-CONCURRENT-IT-A-" + System.nanoTime(), ready, start
            ));
            var second = executor.submit(() -> performConcurrentCheckout(
                    "POS-CONCURRENT-IT-B-" + System.nanoTime(), ready, start
            ));

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Both checkout workers should be ready");
            start.countDown();

            CheckoutOutcome firstOutcome = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            CheckoutOutcome secondOutcome = second.get(20, java.util.concurrent.TimeUnit.SECONDS);

            long successfulCheckouts = java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(CheckoutOutcome::successful)
                    .count();
            long rejectedCheckouts = java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(outcome -> outcome.failure() instanceof InsufficientStockException)
                    .count();

            assertEquals(1, successfulCheckouts,
                    "Exactly one checkout may consume the only available unit");
            assertEquals(1, rejectedCheckouts,
                    "The competing checkout must fail for insufficient stock");
            assertEquals(1, saleRepository.count(),
                    "Only one sale may be persisted");
            assertEquals(1, movementRepository.count(),
                    "Only one stock movement may be persisted");

            InventoryBalance finalBalance = balanceRepository
                    .findByLocationIdAndVariantId(location.getId(), variant.getId())
                    .orElseThrow();
            assertEquals(0, finalBalance.getOnHandQuantity());
            assertEquals(0, finalBalance.getReservedQuantity());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryingSameCheckoutKeyReturnsOriginalSaleWithoutSellingStockAgain() {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(balance);

        PosCheckoutRequest request = buildCheckoutRequest("POS-IDEMPOTENT-REPLAY-IT");
        request.setCustomerName("  Test Customer  ");

        var first = checkoutService.checkout(request, "pos-rollback-test-cashier");

        request.setCustomerName("Test Customer");
        var retry = checkoutService.checkout(request, "pos-rollback-test-cashier");

        assertEquals(first.id(), retry.id(),
                "A retry with the same key and equivalent normalized details must return the original sale");
        assertEquals(1, saleRepository.count());
        assertEquals(1, movementRepository.count());

        InventoryBalance finalBalance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        assertEquals(0, finalBalance.getOnHandQuantity());
        assertEquals(0, finalBalance.getReservedQuantity());
    }

    @Test
    void reusingCheckoutKeyWithChangedDetailsIsRejected() {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(balance);

        PosCheckoutRequest request = buildCheckoutRequest("POS-IDEMPOTENCY-MISMATCH-IT");
        checkoutService.checkout(request, "pos-rollback-test-cashier");

        request.setCustomerName("Different Customer");

        assertThrows(IllegalArgumentException.class,
                () -> checkoutService.checkout(request, "pos-rollback-test-cashier"));

        assertEquals(1, saleRepository.count(),
                "A mismatched retry must not create another sale");
        assertEquals(1, movementRepository.count(),
                "A mismatched retry must not create another stock movement");
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyReturnOneSale() throws Exception {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(balance);

        String sharedKey = "POS-CONCURRENT-SAME-KEY-" + System.nanoTime();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);

        try {
            var first = executor.submit(() ->
                    performConcurrentCheckout(sharedKey, ready, start));
            var second = executor.submit(() ->
                    performConcurrentCheckout(sharedKey, ready, start));

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Both checkout workers should be ready");
            start.countDown();

            CheckoutOutcome firstOutcome = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            CheckoutOutcome secondOutcome = second.get(20, java.util.concurrent.TimeUnit.SECONDS);

            assertTrue(firstOutcome.successful(),
                    "First same-key checkout should succeed: " + firstOutcome.failure());
            assertTrue(secondOutcome.successful(),
                    "Concurrent retry with the same key should return the original sale: "
                            + secondOutcome.failure());
            assertEquals(1, saleRepository.count(),
                    "Concurrent requests with one idempotency key must create one sale");
            assertEquals(1, movementRepository.count(),
                    "Concurrent retries must create one stock movement");

            InventoryBalance finalBalance = balanceRepository
                    .findByLocationIdAndVariantId(location.getId(), variant.getId())
                    .orElseThrow();
            assertEquals(0, finalBalance.getOnHandQuantity());
            assertEquals(0, finalBalance.getReservedQuantity());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameIdempotencyKeyAcrossDifferentLocationsCreatesOnlyOneSale() throws Exception {
        InventoryBalance firstBalance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        firstBalance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(firstBalance);

        InventoryLocation secondLocation = new InventoryLocation();
        secondLocation.setCode("POS-ROLLBACK-TEST-2");
        secondLocation.setName("POS Rollback Test Location 2");
        secondLocation.setActive(true);
        secondLocation = locationRepository.saveAndFlush(secondLocation);

        InventoryBalance secondBalance = new InventoryBalance();
        secondBalance.setLocation(secondLocation);
        secondBalance.setVariant(variant);
        secondBalance.setOnHandQuantity(1);
        secondBalance.setReservedQuantity(0);
        secondBalance.setReorderLevel(0);
        balanceRepository.saveAndFlush(secondBalance);

        String sharedKey = "POS-CROSS-LOCATION-SAME-KEY-" + System.nanoTime();
        PosCheckoutRequest firstRequest = buildCheckoutRequest(sharedKey);
        PosCheckoutRequest secondRequest = buildCheckoutRequest(sharedKey);
        secondRequest.setLocationId(secondLocation.getId());

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);

        try {
            var first = executor.submit(() ->
                    performConcurrentCheckout(firstRequest, ready, start));
            var second = executor.submit(() ->
                    performConcurrentCheckout(secondRequest, ready, start));

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Both cross-location checkout workers should be ready");
            start.countDown();

            CheckoutOutcome firstOutcome = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            CheckoutOutcome secondOutcome = second.get(20, java.util.concurrent.TimeUnit.SECONDS);

            long successfulCheckouts = java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(CheckoutOutcome::successful)
                    .count();
            long rejectedCheckouts = java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(outcome -> !outcome.successful())
                    .count();

            assertEquals(1, successfulCheckouts,
                    "Only one location may create a sale for a globally unique idempotency key");
            assertEquals(1, rejectedCheckouts,
                    "The competing location must reject reuse of the same key");
            assertEquals(1, saleRepository.count(),
                    "Cross-location retries must create exactly one sale");
            assertEquals(1, movementRepository.count(),
                    "Cross-location retries must create exactly one stock movement");

            InventoryBalance finalFirstBalance = balanceRepository
                    .findByLocationIdAndVariantId(location.getId(), variant.getId())
                    .orElseThrow();
            InventoryBalance finalSecondBalance = balanceRepository
                    .findByLocationIdAndVariantId(secondLocation.getId(), variant.getId())
                    .orElseThrow();

            assertEquals(1, finalFirstBalance.getOnHandQuantity()
                            + finalSecondBalance.getOnHandQuantity(),
                    "Exactly one location should have consumed its unit");
            assertEquals(0, finalFirstBalance.getReservedQuantity());
            assertEquals(0, finalSecondBalance.getReservedQuantity());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReturnsCannotReturnTheSameSaleUnitTwice() throws Exception {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(1);
        balanceRepository.saveAndFlush(balance);

        var saleResponse = checkoutService.checkout(
                buildCheckoutRequest("POS-RETURN-CONCURRENCY-SALE-" + System.nanoTime()),
                "pos-rollback-test-cashier"
        );
        Long saleItemId = saleItemRepository.findBySaleId(saleResponse.id())
                .stream().findFirst().orElseThrow().getId();

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);

        try {
            var first = executor.submit(() -> performConcurrentReturn(
                    saleResponse.id(), saleItemId,
                    "POS-RETURN-CONCURRENT-A-" + System.nanoTime(), ready, start
            ));
            var second = executor.submit(() -> performConcurrentReturn(
                    saleResponse.id(), saleItemId,
                    "POS-RETURN-CONCURRENT-B-" + System.nanoTime(), ready, start
            ));

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Both return workers should be ready");
            start.countDown();

            ReturnOutcome firstOutcome = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            ReturnOutcome secondOutcome = second.get(20, java.util.concurrent.TimeUnit.SECONDS);

            long successfulReturns = java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(ReturnOutcome::successful)
                    .count();
            long rejectedReturns = java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(outcome -> outcome.failure() instanceof IllegalArgumentException)
                    .count();

            assertEquals(1, successfulReturns,
                    "Exactly one return may consume the original sold quantity");
            assertEquals(1, rejectedReturns,
                    "The competing return must be rejected after the sale lock is acquired");
            assertEquals(1, returnRepository.count(),
                    "Only one return record may be committed");
            assertEquals(1, returnItemRepository.count(),
                    "Only one return line may be committed");
            assertEquals(2, movementRepository.count(),
                    "The sale and single successful return must each create one stock movement");

            InventoryBalance finalBalance = balanceRepository
                    .findByLocationIdAndVariantId(location.getId(), variant.getId())
                    .orElseThrow();
            assertEquals(1, finalBalance.getOnHandQuantity(),
                    "The returned unit should be restocked exactly once");
            assertEquals(0, finalBalance.getReservedQuantity());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private ReturnOutcome performConcurrentReturn(
            Long saleId,
            Long saleItemId,
            String idempotencyKey,
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                return new ReturnOutcome(false,
                        new IllegalStateException("Timed out waiting to start return"));
            }
            PosReturnRequest request = new PosReturnRequest(
                    shiftId,
                    "Concurrent return limit integration test",
                    idempotencyKey,
                    List.of(new PosReturnItemRequest(saleItemId, 1, "RESTOCK", null)),
                    List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("100.00"), null))
            );
            returnService.processReturn(saleId, request, "pos-rollback-test-cashier");
            return new ReturnOutcome(true, null);
        } catch (Throwable failure) {
            return new ReturnOutcome(false, failure);
        }
    }

    private record ReturnOutcome(boolean successful, Throwable failure) {
    }

    private CheckoutOutcome performConcurrentCheckout(
            String idempotencyKey,
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                return new CheckoutOutcome(false,
                        new IllegalStateException("Timed out waiting to start checkout"));
            }
            checkoutService.checkout(buildCheckoutRequest(idempotencyKey),
                    "pos-rollback-test-cashier");
            return new CheckoutOutcome(true, null);
        } catch (Throwable failure) {
            return new CheckoutOutcome(false, failure);
        }
    }

    private CheckoutOutcome performConcurrentCheckout(
            PosCheckoutRequest request,
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                return new CheckoutOutcome(false,
                        new IllegalStateException("Timed out waiting to start checkout"));
            }
            checkoutService.checkout(request, "pos-rollback-test-cashier");
            return new CheckoutOutcome(true, null);
        } catch (Throwable failure) {
            return new CheckoutOutcome(false, failure);
        }
    }


    @Test
    void failedCashRefundRollsBackReturnRecordAndRestockOnMySql() {
        inventoryService.receiveStock(
                location.getId(), variant.getId(), 1,
                "POS-RETURN-ROLLBACK-OPEN-" + System.nanoTime(), null,
                "Opening stock for cash-refund rollback test"
        );

        PosRegisterResponse cashlessRegister = shiftService.createRegister(
                new PosRegisterCreateRequest(location.getId(), "CASHLESS", "Cashless Test Register")
        );
        Long cashlessShiftId = shiftService.openShift(
                new PosShiftOpenRequest(
                        cashlessRegister.id(), new BigDecimal("0.00"),
                        "POS-TEST-CASHLESS-SHIFT-" + System.nanoTime()
                ),
                "pos-rollback-test-cashier"
        ).id();

        PosCheckoutRequest checkout = buildCheckoutRequest("POS-RETURN-ROLLBACK-SALE-" + System.nanoTime());
        checkout.setShiftId(cashlessShiftId);
        PosPaymentRequest upi = new PosPaymentRequest();
        upi.setPaymentMethod("UPI");
        upi.setAmount(new BigDecimal("100.00"));
        upi.setTransactionReference("UPI-SETTLED-ROLLBACK-TEST");
        checkout.setPayments(List.of(upi));

        var sale = checkoutService.checkout(checkout, "pos-rollback-test-cashier");
        Long saleItemId = saleItemRepository.findBySaleId(sale.id()).get(0).getId();
        long movementsBeforeReturn = movementRepository.count();
        long cashMovementsBeforeReturn = cashMovementRepository.count();

        PosReturnRequest request = new PosReturnRequest(
                cashlessShiftId,
                "Test atomic rollback when drawer cannot fund cash refund",
                "POS-RETURN-ROLLBACK-KEY-" + System.nanoTime(),
                List.of(new PosReturnItemRequest(saleItemId, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("100.00"), null))
        );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> returnService.processReturn(
                        sale.id(), request, "pos-rollback-test-cashier", "ADMIN"
                )
        );
        assertTrue(failure.getMessage().contains("exceeds the cash currently expected"));

        assertEquals(0, returnRepository.count(),
                "A failed cash refund must not leave a completed return record");
        assertEquals(0, returnItemRepository.count(),
                "A failed cash refund must not leave return line records");
        assertEquals(0, refundPaymentRepository.count(),
                "A failed cash refund must not leave refund tender records");
        assertEquals(movementsBeforeReturn, movementRepository.count(),
                "The earlier restock movement must roll back with the failed cash refund");
        assertEquals(cashMovementsBeforeReturn, cashMovementRepository.count(),
                "The failed refund must not leave a cash drawer movement");

        InventoryBalance finalBalance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        assertEquals(0, finalBalance.getOnHandQuantity(),
                "The restock must roll back because the drawer refund could not be funded");
        assertEquals(0, finalBalance.getReservedQuantity());
    }


    @Test
    void exchangeReportSeparatesRetailCreditFromActualTenderAndRefundsOnMySql() {
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        balance.setOnHandQuantity(2);
        balanceRepository.saveAndFlush(balance);

        var original = checkoutService.checkout(
                buildCheckoutRequest("POS-REPORT-EXCHANGE-ORIGINAL-" + System.nanoTime()),
                "pos-rollback-test-cashier"
        );
        Long originalItemId = saleItemRepository.findBySaleId(original.id()).get(0).getId();

        PosSaleItemRequest replacementLine = new PosSaleItemRequest();
        replacementLine.setProductId(product.getId());
        replacementLine.setVariantId(variant.getId());
        replacementLine.setQuantity(1);
        PosExchangeRequest exchangeRequest = new PosExchangeRequest(
                shiftId,
                "Equal value exchange report reconciliation",
                "POS-REPORT-EXCHANGE-" + System.nanoTime(),
                List.of(new PosReturnItemRequest(originalItemId, 1, "RESTOCK", null)),
                List.of(replacementLine),
                new BigDecimal("0.00"),
                List.of(),
                List.of()
        );
        exchangeService.exchange(original.id(), exchangeRequest, "pos-rollback-test-cashier", "CASHIER");

        var report = reportService.getSalesReport(location.getId(), LocalDate.now(), LocalDate.now(), "pos-rollback-test-cashier", "CASHIER");

        assertEquals(2, report.transactionCount(),
                "The original sale and replacement sale both represent retail sales");
        assertEquals(new BigDecimal("200.00"), report.netSalesBeforeRefunds());
        assertEquals(new BigDecimal("100.00"), report.refunds(),
                "The returned original item must reduce retail sales once");
        assertEquals(new BigDecimal("100.00"), report.netSalesAfterRefunds());
        assertEquals(new BigDecimal("100.00"), report.exchangeCreditApplied(),
                "Exchange credit is a separate settlement component");
        assertEquals(new BigDecimal("100.00"), report.salesByPaymentMethod().get("CASH"),
                "Only the original cash tender was collected");
        assertTrue(report.refundsByPaymentMethod().isEmpty(),
                "Equal-value exchange credit is not a cash or provider refund");
        assertEquals(new BigDecimal("0.00"), report.recordedRefundPayments());
    }

    private PosCheckoutRequest buildCheckoutRequest(String idempotencyKey) {
        PosCheckoutRequest request = new PosCheckoutRequest();
        request.setLocationId(location.getId());
        request.setShiftId(shiftId);
        request.setIdempotencyKey(idempotencyKey);
        request.setDiscountAmount(new BigDecimal("0.00"));

        PosSaleItemRequest item = new PosSaleItemRequest();
        item.setProductId(product.getId());
        item.setVariantId(variant.getId());
        item.setQuantity(1);
        request.setItems(List.of(item));

        PosPaymentRequest payment = new PosPaymentRequest();
        payment.setPaymentMethod("CASH");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setTenderedAmount(new BigDecimal("100.00"));
        request.setPayments(List.of(payment));
        return request;
    }

    private record CheckoutOutcome(boolean successful, Throwable failure) {
    }

}


package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.exception.*;
import nmk_backend.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryLocationRepository locationRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository variantRepository;

    @Mock
    private InventoryBalanceRepository balanceRepository;

    @Mock
    private StockMovementRepository movementRepository;

    @InjectMocks
    private InventoryService inventoryService;

    private InventoryLocation location;
    private Product product;
    private ProductVariant variant;
    private InventoryBalance balance;

    @BeforeEach
    void setUp() {
        location = new InventoryLocation();
        location.setId(1L);
        location.setCode("MAIN");
        location.setName("Main Store");
        location.setActive(true);

        product = new Product();
        product.setId(3L);
        product.setName("Test Frame");
        product.setActive(true);

        variant = new ProductVariant();
        variant.setId(2L);
        variant.setSku("TEST-SKU");
        variant.setVariantName("Standard");
        variant.setActive(true);
        variant.setProduct(product);

        balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(10);
        balance.setReservedQuantity(2);
        balance.setReorderLevel(1);
    }

    private void stubActiveProductAndVariant() {
        when(variantRepository.findProductIdByVariantId(2L))
                .thenReturn(Optional.of(3L));

        when(productRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.of(product));

        when(variantRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(variant));
    }

    private void stubLocation() {
        when(locationRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(location));
    }

    private void stubNewMovement() {
        when(movementRepository.findByIdempotencyKey(any()))
                .thenReturn(Optional.empty());
    }

    private void stubExistingBalance() {
        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.of(balance));
    }

    @Test
    void receiveStockIncreasesOnHandQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.receiveStock(
                1L, 2L, 5, "RECEIVE-001", 10L, "New stock"
        );

        assertEquals(15, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(13, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("RECEIPT", captor.getValue().getMovementType());
        assertEquals(5, captor.getValue().getQuantityDelta());
        assertEquals(0, captor.getValue().getReservedDelta());
        assertEquals(15, captor.getValue().getOnHandAfter());
    }

    @Test
    void reserveStockIncreasesReservedWithoutReducingOnHand() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.reserveStock(
                1L, 2L, 3, "RESERVE-001", null, "Online order"
        );

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(5, result.getReservedQuantity());
        assertEquals(5, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("RESERVATION", captor.getValue().getMovementType());
        assertEquals(0, captor.getValue().getQuantityDelta());
        assertEquals(3, captor.getValue().getReservedDelta());
    }

    @Test
    void releaseReservationReducesOnlyReservedQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.releaseReservation(
                1L, 2L, 2, "RELEASE-001", null, "Order cancelled"
        );

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(3, result.getReservedQuantity());
        assertEquals(7, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("RELEASE", captor.getValue().getMovementType());
        assertEquals(0, captor.getValue().getQuantityDelta());
        assertEquals(-2, captor.getValue().getReservedDelta());
    }

    @Test
    void consumeReservationReducesBothQuantities() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.consumeReservation(
                1L, 2L, 3, "CONSUME-001", null, "Payment confirmed"
        );

        assertEquals(7, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(5, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("CONSUMPTION", captor.getValue().getMovementType());
        assertEquals(-3, captor.getValue().getQuantityDelta());
        assertEquals(-3, captor.getValue().getReservedDelta());
    }

    @Test
    void reserveStockRejectsInsufficientAvailableQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setOnHandQuantity(5);
        balance.setReservedQuantity(4);
        stubExistingBalance();

        assertThrows(
                InsufficientStockException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 2, "RESERVE-002", null, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void releaseReservationRejectsInsufficientReservedQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setReservedQuantity(1);
        stubExistingBalance();

        assertThrows(
                InsufficientStockException.class,
                () -> inventoryService.releaseReservation(
                        1L, 2L, 2, "RELEASE-002", null, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockReducesOnHandButPreservesReservedQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.sellStock(
                1L, 2L, 5, "POS-SALE-001", 10L,
                "POS sale", "POS_SALE", 100L
        );

        assertSame(balance, result);
        assertEquals(5, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());
        assertEquals(3, balance.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        StockMovement movement = captor.getValue();

        assertEquals("SALE", movement.getMovementType());
        assertEquals(-5, movement.getQuantityDelta());
        assertEquals(0, movement.getReservedDelta());
        assertEquals("POS-SALE-001", movement.getIdempotencyKey());
        assertEquals("POS_SALE", movement.getReferenceType());
        assertEquals(100L, movement.getReferenceId());
    }

    @Test
    void updatesLocationSpecificReorderLevelWithActorAudit() {
        stubLocation();
        when(variantRepository.findById(2L)).thenReturn(Optional.of(variant));
        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(6);
        balance.setReservedQuantity(2);
        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.of(balance));
        when(balanceRepository.saveAndFlush(any(InventoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryBalance result = inventoryService.updateReorderLevel(
                1L, 2L, 5, "manager.one"
        );

        assertEquals(5, result.getReorderLevel());
        assertEquals("manager.one", result.getReorderLevelUpdatedBy());
        assertNotNull(result.getReorderLevelUpdatedAt());
        assertEquals(4, result.getAvailableQuantity());
    }

    @Test
    void lowStockAlertsOnlyIncludeConfiguredThresholdsAtOrBelowAvailableQuantity() {
        InventoryBalance low = new InventoryBalance();
        low.setLocation(location);
        low.setVariant(variant);
        low.setOnHandQuantity(5);
        low.setReservedQuantity(2);
        low.setReorderLevel(3);

        ProductVariant secondVariant = new ProductVariant();
        secondVariant.setId(3L);
        secondVariant.setProduct(variant.getProduct());
        secondVariant.setSku("FRAME-003");
        InventoryBalance above = new InventoryBalance();
        above.setLocation(location);
        above.setVariant(secondVariant);
        above.setOnHandQuantity(10);
        above.setReservedQuantity(0);
        above.setReorderLevel(3);

        ProductVariant noAlertVariant = new ProductVariant();
        noAlertVariant.setId(4L);
        noAlertVariant.setProduct(variant.getProduct());
        noAlertVariant.setSku("FRAME-004");
        InventoryBalance noAlert = new InventoryBalance();
        noAlert.setLocation(location);
        noAlert.setVariant(noAlertVariant);
        noAlert.setOnHandQuantity(0);
        noAlert.setReservedQuantity(0);
        noAlert.setReorderLevel(0);

        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of(above, noAlert, low));

        var alerts = inventoryService.getLowStockBalances(1L);

        assertEquals(1, alerts.size());
        assertEquals("TEST-SKU", alerts.get(0).sku());
        assertEquals(3, alerts.get(0).availableQuantity());
    }

    @Test
    void stockCountFreezeBlocksNewInventoryMovements() {
        stubLocation();
        location.setStockCountLocked(true);
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(InventoryConflictException.class, () ->
                inventoryService.adjustStock(
                        1L, 2L, 1, "COUNT-FROZEN-001", 10L,
                        "Attempt during stock count"
                )
        );
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void customerReturnRestockAddsOnlyInspectedResaleableUnits() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.restockCustomerReturn(
                1L, 2L, 1, "RETURN-STOCK-001", 10L,
                "Inspected resaleable customer return", 501L
        );

        assertEquals(11, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(9, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());

        StockMovement movement = captor.getValue();
        assertEquals("CUSTOMER_RETURN_RESTOCK", movement.getMovementType());
        assertEquals(1, movement.getQuantityDelta());
        assertEquals(0, movement.getReservedDelta());
        assertEquals("POS_RETURN", movement.getReferenceType());
        assertEquals(501L, movement.getReferenceId());
    }

    @Test
    void sellStockRejectsInsufficientAvailableQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        assertThrows(
                InsufficientStockException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 9, "POS-SALE-002", 10L,
                        "POS sale", null, null
                )
        );

        assertEquals(10, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsMissingBalance() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.empty());

        assertThrows(
                InventoryNotFoundException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-003", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsInactiveProduct() {
        product.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-004", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void identicalSaleRetryDoesNotDeductStockTwice() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("SALE");
        existing.setQuantityDelta(-3);
        existing.setReservedDelta(0);
        existing.setActorStaffId(10L);
        existing.setReason("Retry POS sale");

        when(movementRepository.findByIdempotencyKey("POS-SALE-RETRY"))
                .thenReturn(Optional.of(existing));

        when(balanceRepository.findByLocationIdAndVariantId(1L, 2L))
                .thenReturn(Optional.of(balance));

        InventoryBalance result = inventoryService.sellStock(
                1L, 2L, 3, "POS-SALE-RETRY", 10L,
                "Retry POS sale", null, null
        );

        assertSame(balance, result);
        assertEquals(10, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void repeatedIdenticalOperationDoesNotCreateAnotherMovement() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RESERVATION");
        existing.setQuantityDelta(0);
        existing.setReservedDelta(3);
        existing.setActorStaffId(null);
        existing.setReason("Retry");

        when(movementRepository.findByIdempotencyKey("RESERVE-RETRY"))
                .thenReturn(Optional.of(existing));

        when(balanceRepository.findByLocationIdAndVariantId(1L, 2L))
                .thenReturn(Optional.of(balance));

        InventoryBalance result = inventoryService.reserveStock(
                1L, 2L, 3, "RESERVE-RETRY", null, "Retry"
        );

        assertSame(balance, result);

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void reusingIdempotencyKeyWithDifferentAuditDetailsIsRejected() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RECEIPT");
        existing.setQuantityDelta(5);
        existing.setReservedDelta(0);
        existing.setActorStaffId(10L);
        existing.setReason("Supplier delivery");

        when(movementRepository.findByIdempotencyKey("RECEIPT-AUDIT-KEY"))
                .thenReturn(Optional.of(existing));

        assertThrows(InventoryConflictException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5, "RECEIPT-AUDIT-KEY", 10L,
                        "Different supplier delivery reason"
                ));

        assertThrows(InventoryConflictException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5, "RECEIPT-AUDIT-KEY", 11L,
                        "Supplier delivery"
                ));

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void reusingIdempotencyKeyForDifferentOperationIsRejected() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RECEIPT");
        existing.setQuantityDelta(5);
        existing.setReservedDelta(0);

        when(movementRepository.findByIdempotencyKey("REUSED-KEY"))
                .thenReturn(Optional.of(existing));

        assertThrows(
                InventoryConflictException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 3, "REUSED-KEY", null, "Different operation"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void zeroQuantityIsRejected() {
        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 0, "ZERO-001", null, "Invalid"
                )
        );

        verifyNoInteractions(
                locationRepository,
                productRepository,
                variantRepository,
                balanceRepository,
                movementRepository
        );
    }

    @Test
    void reservationRejectsMissingBalance() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.empty());

        assertThrows(
                InventoryNotFoundException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 1, "MISSING-BALANCE", null, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void receivingStockThatExceedsIntegerLimitIsRejected() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setOnHandQuantity(Integer.MAX_VALUE);
        balance.setReservedQuantity(0);
        stubExistingBalance();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 1, "OVERFLOW-001", null, "Overflow test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void inventoryOperationRejectsInactiveParentProduct() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        product.setActive(false);

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "INACTIVE-PRODUCT-001", 10L, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void inventoryOperationRejectsInactiveVariant() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        variant.setActive(false);

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "INACTIVE-VARIANT-001", 10L, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void inventoryOperationRejectsInactiveLocation() {
        location.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "INACTIVE-LOCATION-001", 10L, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void missingParentProductIsRejected() {
        stubLocation();

        when(variantRepository.findProductIdByVariantId(2L))
                .thenReturn(Optional.of(3L));

        when(productRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.empty());

        assertThrows(
                InventoryNotFoundException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "MISSING-PRODUCT-001", 10L, "Test"
                )
        );

        verify(variantRepository, never()).findByIdForUpdate(2L);
        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void releaseReservationWorksAfterParentProductIsDeactivated() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        product.setActive(false);

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.releaseReservation(
                1L, 2L, 2,
                "RELEASE-INACTIVE-PRODUCT-001",
                null, "Release after product deactivation"
        );

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(3, result.getReservedQuantity());
        assertEquals(7, result.getAvailableQuantity());

        verify(balanceRepository).saveAndFlush(balance);
        verify(movementRepository).saveAndFlush(any(StockMovement.class));
    }

    @Test
    void consumeReservationWorksAfterVariantIsDeactivated() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        variant.setActive(false);

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.consumeReservation(
                1L, 2L, 3,
                "CONSUME-INACTIVE-VARIANT-001",
                null, "Payment confirmed after variant deactivation"
        );

        assertEquals(7, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(5, result.getAvailableQuantity());

        verify(balanceRepository).saveAndFlush(balance);
        verify(movementRepository).saveAndFlush(any(StockMovement.class));
    }

    @Test
    void identicalReservationRetryWorksAfterRecordsAreDeactivated() {
        stubLocation();
        stubActiveProductAndVariant();

        location.setActive(false);
        product.setActive(false);
        variant.setActive(false);

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RESERVATION");
        existing.setQuantityDelta(0);
        existing.setReservedDelta(3);
        existing.setActorStaffId(null);
        existing.setReason("Retry existing reservation");

        when(movementRepository.findByIdempotencyKey("RESERVE-INACTIVE-RETRY"))
                .thenReturn(Optional.of(existing));

        when(balanceRepository.findByLocationIdAndVariantId(1L, 2L))
                .thenReturn(Optional.of(balance));

        InventoryBalance result = inventoryService.reserveStock(
                1L, 2L, 3,
                "RESERVE-INACTIVE-RETRY",
                null, "Retry existing reservation"
        );

        assertSame(balance, result);

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void newReservationIsRejectedWhenParentProductIsInactive() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        product.setActive(false);

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 2,
                        "RESERVE-INACTIVE-PRODUCT-001",
                        null, "New reservation"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }
    @Test
    void sellStockRejectsInactiveVariant() {
        variant.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-005", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsInactiveLocation() {
        location.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-006", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsReusedIdempotencyKeyForDifferentOperation() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("SALE");
        existing.setQuantityDelta(-2);
        existing.setReservedDelta(0);
        existing.setReferenceType("POS_SALE");
        existing.setReferenceId(100L);

        when(movementRepository.findByIdempotencyKey("POS-SALE-CONFLICT"))
                .thenReturn(Optional.of(existing));

        assertThrows(
                InventoryConflictException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 3, "POS-SALE-CONFLICT", 10L,
                        "Different sale", "POS_SALE", 101L
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void movementHistoryReturnsPaginatedAuditedStockLedgerRows() {
        when(locationRepository.findById(1L)).thenReturn(Optional.of(location));
        StockMovement movement = new StockMovement();
        ReflectionTestUtils.setField(movement, "id", 44L);
        movement.setLocation(location);
        movement.setVariant(variant);
        movement.setMovementType("RECEIPT");
        movement.setQuantityDelta(5);
        movement.setReservedDelta(0);
        movement.setOnHandAfter(15);
        movement.setReservedAfter(2);
        movement.setReferenceType("GOODS_RECEIPT");
        movement.setReferenceId(9L);
        movement.setActorStaffId(10L);
        movement.setReason("Supplier delivery checked");
        when(movementRepository.findHistory(1L, null, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(movement), PageRequest.of(0, 50), 1));

        var result = inventoryService.getMovementHistory(1L, null, 0, 50);

        assertEquals(1, result.getTotalElements());
        assertEquals("Test Frame", result.getContent().get(0).productName());
        assertEquals("TEST-SKU", result.getContent().get(0).sku());
        assertEquals("RECEIPT", result.getContent().get(0).movementType());
        assertEquals(5, result.getContent().get(0).quantityDelta());
        assertEquals(15, result.getContent().get(0).onHandAfter());
        assertEquals("GOODS_RECEIPT", result.getContent().get(0).referenceType());
    }

    @Test
    void movementHistoryRejectsInvalidPageSize() {
        assertThrows(InvalidInventoryOperationException.class,
                () -> inventoryService.getMovementHistory(1L, null, 0, 101));
        verifyNoInteractions(movementRepository);
    }


    @Test
    void movementHistoryRejectsUnknownLocation() {
        when(locationRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(InventoryNotFoundException.class,
                () -> inventoryService.getMovementHistory(999L, null, 0, 50));
        verifyNoInteractions(movementRepository);
    }

}

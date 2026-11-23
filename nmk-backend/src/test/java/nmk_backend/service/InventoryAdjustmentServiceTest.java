package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.exception.InvalidInventoryOperationException;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryAdjustmentServiceTest {

    private InventoryLocationRepository locationRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private InventoryBalanceRepository balanceRepository;
    private StockMovementRepository movementRepository;
    private InventoryService service;

    private InventoryLocation location;
    private Product product;
    private ProductVariant variant;
    private InventoryBalance balance;

    @BeforeEach
    void setUp() {
        locationRepository = mock(InventoryLocationRepository.class);
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        balanceRepository = mock(InventoryBalanceRepository.class);
        movementRepository = mock(StockMovementRepository.class);
        service = new InventoryService(
                locationRepository, productRepository, variantRepository,
                balanceRepository, movementRepository
        );

        location = new InventoryLocation();
        location.setId(1L);
        location.setActive(true);

        product = new Product();
        product.setId(10L);
        product.setActive(true);

        variant = new ProductVariant();
        variant.setId(20L);
        variant.setProduct(product);
        variant.setActive(true);

        balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(12);
        balance.setReservedQuantity(3);
        balance.setReorderLevel(0);

        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(variantRepository.findProductIdByVariantId(20L)).thenReturn(Optional.of(10L));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(variantRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(variant));
        when(movementRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 20L))
                .thenReturn(Optional.of(balance));
    }

    @Test
    void positiveAdjustmentIncreasesOnHandAndWritesLedgerEntry() {
        InventoryBalance result = service.adjustStock(
                1L, 20L, 5, "COUNT-ADJUST-1", 7L, "Physical count found five extra"
        );

        assertEquals(17, result.getOnHandQuantity());
        assertEquals(3, result.getReservedQuantity());

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());
        StockMovement movement = captor.getValue();
        assertEquals("ADJUSTMENT", movement.getMovementType());
        assertEquals(5, movement.getQuantityDelta());
        assertEquals(0, movement.getReservedDelta());
        assertEquals(17, movement.getOnHandAfter());
        assertEquals(7L, movement.getActorStaffId());
        assertEquals("Physical count found five extra", movement.getReason());
        assertEquals("COUNT-ADJUST-1", movement.getIdempotencyKey());
    }

    @Test
    void negativeAdjustmentCannotReduceOnHandBelowReservedQuantity() {
        assertThrows(InvalidInventoryOperationException.class, () ->
                service.adjustStock(
                        1L, 20L, -10, "COUNT-ADJUST-2", 7L, "Damaged stock"
                )
        );

        assertEquals(12, balance.getOnHandQuantity());
        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void zeroAdjustmentIsRejected() {
        assertThrows(InvalidInventoryOperationException.class, () ->
                service.adjustStock(1L, 20L, 0, "COUNT-ADJUST-3", 7L, "No difference")
        );
        verifyNoInteractions(locationRepository);
    }

    @Test
    void missingReasonIsRejected() {
        assertThrows(InvalidInventoryOperationException.class, () ->
                service.adjustStock(1L, 20L, -1, "COUNT-ADJUST-4", 7L, "  ")
        );
        verifyNoInteractions(locationRepository);
    }
}

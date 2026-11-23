package nmk_backend.service;

import nmk_backend.dto.StockCountStartRequest;
import nmk_backend.dto.StockCountLineRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockCountServiceTest {

    private InventoryLocationRepository locationRepository;
    private InventoryBalanceRepository balanceRepository;
    private ProductVariantRepository variantRepository;
    private StockCountSessionRepository sessionRepository;
    private StockCountLineRepository lineRepository;
    private InventoryService inventoryService;
    private AdminRepository adminRepository;
    private StockCountService service;

    private InventoryLocation location;
    private ProductVariant variant;
    private StockCountSession session;
    private StockCountLine line;
    private Admin admin;

    @BeforeEach
    void setUp() {
        locationRepository = mock(InventoryLocationRepository.class);
        balanceRepository = mock(InventoryBalanceRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        sessionRepository = mock(StockCountSessionRepository.class);
        lineRepository = mock(StockCountLineRepository.class);
        inventoryService = mock(InventoryService.class);
        adminRepository = mock(AdminRepository.class);

        service = new StockCountService(
                locationRepository, balanceRepository, variantRepository,
                sessionRepository, lineRepository, inventoryService, adminRepository
        );

        location = new InventoryLocation();
        location.setId(1L);
        location.setName("Main Store");
        location.setActive(true);

        Product product = new Product();
        product.setName("Classic Frame");
        variant = new ProductVariant();
        ReflectionTestUtils.setField(variant, "id", 5L);
        variant.setProduct(product);
        variant.setSku("FRAME-005");
        variant.setVariantName("Black");

        session = new StockCountSession();
        ReflectionTestUtils.setField(session, "id", 8L);
        session.setLocation(location);
        session.setStatus("READY_FOR_REVIEW");
        session.setReason("Quarterly physical count");
        session.setStartedByUsername("manager.one");

        line = new StockCountLine();
        ReflectionTestUtils.setField(line, "id", 80L);
        line.setSession(session);
        line.setVariant(variant);
        line.setSnapshotOnHand(10);
        line.setCountedQuantity(12);
        session.getLines().add(line);

        admin = new Admin();
        admin.setId(3L);
        admin.setUsername("admin.one");
    }

    @Test
    void startingCountFreezesLocationAndCapturesExpectedStock() {
        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(10);
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findFirstByLocationIdAndStatusInOrderByStartedAtDesc(eq(1L), anyList()))
                .thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(StockCountSession.class)))
                .thenAnswer(invocation -> {
                    StockCountSession value = invocation.getArgument(0);
                    ReflectionTestUtils.setField(value, "id", 8L);
                    return value;
                });
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of(balance));
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(lineRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.start(
                new StockCountStartRequest(1L, "Quarterly physical count"),
                "manager.one"
        );

        assertTrue(location.isStockCountLocked());
        assertEquals("COUNTING", response.status());
        assertEquals(1, response.lineCount());
        assertEquals(10, response.lines().get(0).snapshotOnHand());
        assertNull(response.lines().get(0).countedQuantity());
    }

    @Test
    void postingCountAppliesVarianceAndReleasesLocationFreeze() {
        location.setStockCountLocked(true);
        when(sessionRepository.findById(8L)).thenReturn(Optional.of(session));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));
        when(lineRepository.findBySessionIdOrderByIdAsc(8L)).thenReturn(List.of(line));
        when(adminRepository.findByUsernameIgnoreCase("admin.one")).thenReturn(Optional.of(admin));
        when(sessionRepository.saveAndFlush(any(StockCountSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.post(8L, "admin.one");

        assertFalse(location.isStockCountLocked());
        assertEquals("POSTED", response.status());
        assertEquals("admin.one", response.postedByUsername());
        verify(inventoryService).applyStockCountVariance(
                1L, 5L, 2, "STOCK-COUNT:8:5", 3L,
                "Physical stock count session 8: Quarterly physical count", 8L
        );
    }

    @Test
    void concurrentPostRetryReplaysAlreadyPostedLockedSession() {
        StockCountSession staleSnapshot = new StockCountSession();
        ReflectionTestUtils.setField(staleSnapshot, "id", 8L);
        staleSnapshot.setLocation(location);
        staleSnapshot.setStatus("READY_FOR_REVIEW");

        session.setStatus("POSTED");
        session.setPostedByUsername("admin.one");
        location.setStockCountLocked(false);

        when(sessionRepository.findById(8L)).thenReturn(Optional.of(staleSnapshot));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));

        var response = service.post(8L, "admin.two");

        assertEquals("POSTED", response.status());
        assertEquals("admin.one", response.postedByUsername());
        verifyNoInteractions(inventoryService);
        verify(sessionRepository, never()).saveAndFlush(any(StockCountSession.class));
    }

    @Test
    void concurrentCancelRetryReplaysAlreadyCancelledLockedSession() {
        StockCountSession staleSnapshot = new StockCountSession();
        ReflectionTestUtils.setField(staleSnapshot, "id", 8L);
        staleSnapshot.setLocation(location);
        staleSnapshot.setStatus("COUNTING");

        session.setStatus("CANCELLED");
        session.setCancelledByUsername("manager.one");
        location.setStockCountLocked(false);

        when(sessionRepository.findById(8L)).thenReturn(Optional.of(staleSnapshot));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));

        var response = service.cancel(8L, "manager.two");

        assertEquals("CANCELLED", response.status());
        assertEquals("manager.one", response.cancelledByUsername());
        verify(sessionRepository, never()).saveAndFlush(any(StockCountSession.class));
    }

    @Test
    void anotherStaffMemberCannotOverwriteTheOriginalCountersAuditIdentity() {
        session.setStatus("COUNTING");
        line.setCountedByUsername("manager.one");
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));
        when(lineRepository.findBySessionAndIdForUpdate(8L, 80L)).thenReturn(Optional.of(line));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.recordCount(8L, 80L, new StockCountLineRequest(13), "manager.two")
        );

        assertTrue(error.getMessage().contains("first counted this line"));
        assertEquals(12, line.getCountedQuantity());
        assertEquals("manager.one", line.getCountedByUsername());
        verify(lineRepository, never()).saveAndFlush(any(StockCountLine.class));
    }

    @Test
    void cancellationRecordsTheAuthenticatedActorAndTimestamp() {
        location.setStockCountLocked(true);
        when(sessionRepository.findById(8L)).thenReturn(Optional.of(session));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(any(StockCountSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.cancel(8L, "manager.cancel");

        assertEquals("CANCELLED", response.status());
        assertEquals("manager.cancel", response.cancelledByUsername());
        assertNotNull(response.cancelledAt());
        assertFalse(location.isStockCountLocked());
        verify(sessionRepository).saveAndFlush(session);
    }

    @Test
    void submitterCannotApproveAndPostTheirOwnStockCount() {
        session.setSubmittedByUsername("manager.one");
        session.setStartedByUsername("manager.one");
        line.setCountedByUsername("manager.one");
        location.setStockCountLocked(true);
        when(sessionRepository.findById(8L)).thenReturn(Optional.of(session));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));
        when(lineRepository.findBySessionIdOrderByIdAsc(8L)).thenReturn(List.of(line));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.post(8L, "MANAGER.ONE")
        );

        assertTrue(error.getMessage().contains("different manager or administrator"));
        assertTrue(location.isStockCountLocked());
        verifyNoInteractions(inventoryService);
        verifyNoInteractions(adminRepository);
    }

    @Test
    void counterCannotApproveTheirOwnPhysicalCount() {
        session.setSubmittedByUsername("manager.two");
        session.setStartedByUsername("manager.two");
        line.setCountedByUsername("admin.one");
        location.setStockCountLocked(true);
        when(sessionRepository.findById(8L)).thenReturn(Optional.of(session));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));
        when(lineRepository.findBySessionIdOrderByIdAsc(8L)).thenReturn(List.of(line));

        assertThrows(IllegalArgumentException.class, () -> service.post(8L, "admin.one"));
        assertTrue(location.isStockCountLocked());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void postCannotProceedUntilEveryLineHasBeenCounted() {
        location.setStockCountLocked(true);
        line.setCountedQuantity(null);
        when(sessionRepository.findById(8L)).thenReturn(Optional.of(session));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(sessionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(session));
        when(lineRepository.findBySessionIdOrderByIdAsc(8L)).thenReturn(List.of(line));

        assertThrows(IllegalArgumentException.class, () -> service.post(8L, "admin.one"));
        assertTrue(location.isStockCountLocked());
        verifyNoInteractions(inventoryService);
    }
}
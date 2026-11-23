package nmk_backend.service;

import nmk_backend.dto.PosCashMovementRequest;
import nmk_backend.dto.PosShiftCloseRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PosShiftServiceTest {

    private InventoryLocationRepository locationRepository;
    private PosRegisterRepository registerRepository;
    private PosShiftRepository shiftRepository;
    private PosCashMovementRepository movementRepository;
    private PosShiftService service;
    private PosShift shift;

    @BeforeEach
    void setUp() {
        locationRepository = mock(InventoryLocationRepository.class);
        registerRepository = mock(PosRegisterRepository.class);
        shiftRepository = mock(PosShiftRepository.class);
        movementRepository = mock(PosCashMovementRepository.class);
        service = new PosShiftService(
                locationRepository, registerRepository, shiftRepository, movementRepository
        );

        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setActive(true);

        PosRegister register = new PosRegister();
        ReflectionTestUtils.setField(register, "id", 3L);
        register.setLocation(location);
        register.setRegisterCode("MAIN");
        register.setActive(true);

        shift = new PosShift();
        ReflectionTestUtils.setField(shift, "id", 9L);
        shift.setRegister(register);
        shift.setOpenedByUsername("cashier.one");
        shift.setOpeningFloat(new BigDecimal("100.00"));
        shift.setStatus("OPEN");
        shift.setIdempotencyKey("SHIFT-OPEN-1");
    }

    @Test
    void dailyCashReconciliationUsesConfiguredBusinessTimezone() {
        PosShiftService configuredService = new PosShiftService(
                locationRepository, registerRepository, shiftRepository, movementRepository,
                null, "Pacific/Auckland"
        );

        assertEquals(java.time.ZoneId.of("Pacific/Auckland"),
                ReflectionTestUtils.getField(configuredService, "businessZone"));
    }

    @Test
    void concurrentShiftOpenRetryReplaysAfterRegisterLock() {
        shift.setIdempotencyKey("SHIFT-OPEN-RACE");
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("100.00"));
        when(shiftRepository.findByIdempotencyKey("SHIFT-OPEN-RACE"))
                .thenReturn(Optional.empty(), Optional.of(shift));
        when(registerRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.of(shift.getRegister()));

        var response = service.openShift(
                new nmk_backend.dto.PosShiftOpenRequest(
                        3L, new BigDecimal("100.00"), "SHIFT-OPEN-RACE"
                ),
                "cashier.one"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("100.00"), response.expectedCash());
        verify(shiftRepository, times(2)).findByIdempotencyKey("SHIFT-OPEN-RACE");
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
    }

    @Test
    void openingFloatCannotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> service.openShift(
                new nmk_backend.dto.PosShiftOpenRequest(
                        3L, new BigDecimal("-0.01"), "SHIFT-NEGATIVE-FLOAT"
                ),
                "cashier.one"
        ));
        verifyNoInteractions(registerRepository, shiftRepository, movementRepository);
    }

    @Test
    void closingCashCountCannotBeNegative() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        assertThrows(IllegalArgumentException.class, () -> service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("-0.01"), "Invalid negative count"),
                "cashier.one",
                "CASHIER"
        ));
        verify(movementRepository, never()).findByShiftIdOrderByCreatedAtAscIdAsc(9L);
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
    }

    @Test
    void anotherManagerCannotReplayACashMovementCreatedByDifferentStaff() {
        PosCashMovement existing = movement("CASH_OUT", "OUT", "25.00", shift);
        existing.setIdempotencyKey("CASH-OUT-3");
        existing.setReason("Store supplies");
        when(movementRepository.findByIdempotencyKey("CASH-OUT-3")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.recordCashMovement(
                9L,
                new PosCashMovementRequest("CASH_OUT", new BigDecimal("25.00"),
                        "CASH-OUT-3", "Store supplies"),
                "manager.one",
                "MANAGER"
        ));
        verify(shiftRepository, never()).findByIdForUpdate(anyLong());
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void anotherStaffMemberCannotReplayCashRefundMovement() {
        PosCashMovement existing = movement("CASH_REFUND", "OUT", "25.00", shift);
        existing.setIdempotencyKey("POS-RETURN-CASH:return-key:0");
        existing.setReferenceId(77L);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("POS-RETURN-CASH:return-key:0"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.recordCashRefund(
                shift, new BigDecimal("25.00"), "POS-RETURN-CASH:return-key:0",
                "manager.two", 77L
        ));
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void concurrentCashMovementRetryReplaysAfterTheShiftHasClosed() {
        PosCashMovement existing = movement("CASH_OUT", "OUT", "25.00", shift);
        existing.setIdempotencyKey("CASH-OUT-RACE");
        existing.setReason("Store supplies");
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("75.00"));

        when(movementRepository.findByIdempotencyKey("CASH-OUT-RACE"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        var response = service.recordCashMovement(
                9L,
                new PosCashMovementRequest("CASH_OUT", new BigDecimal("25.00"),
                        "CASH-OUT-RACE", "Store supplies"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("75.00"), response.expectedCash());
        verify(movementRepository, times(2)).findByIdempotencyKey("CASH-OUT-RACE");
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void cashOutCannotExceedCurrentExpectedDrawerCash() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("CASH-OUT-1")).thenReturn(Optional.empty());
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening));

        assertThrows(IllegalArgumentException.class, () ->
                service.recordCashMovement(
                        9L,
                        new PosCashMovementRequest("CASH_OUT", new BigDecimal("101.00"),
                                "CASH-OUT-1", "Petty cash"),
                        "cashier.one",
                        "CASHIER"
                )
        );
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void cashOutIsRecordedAsAnOutflowAndReducesExpectedCash() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("CASH-OUT-2")).thenReturn(Optional.empty());
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening, movement("CASH_OUT", "OUT", "25.00", shift)));
        when(movementRepository.saveAndFlush(any(PosCashMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.recordCashMovement(
                9L,
                new PosCashMovementRequest("CASH_OUT", new BigDecimal("25.00"),
                        "CASH-OUT-2", "Store supplies"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals(new BigDecimal("75.00"), response.expectedCash());
        verify(movementRepository).saveAndFlush(argThat(movement ->
                "CASH_OUT".equals(movement.getMovementType())
                        && "OUT".equals(movement.getDirection())
                        && new BigDecimal("25.00").compareTo(movement.getAmount()) == 0
        ));
    }

    @Test
    void cashVarianceRequiresAnExplanationAtShiftClose() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening));

        assertThrows(IllegalArgumentException.class, () ->
                service.closeShift(
                        9L,
                        new PosShiftCloseRequest(new BigDecimal("95.00"), null),
                        "cashier.one",
                        "CASHIER"
                )
        );
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void shiftCloseStoresExpectedCashVarianceAndReason() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening));
        when(shiftRepository.saveAndFlush(any(PosShift.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("95.00"), "Five rupees short; recounted drawer"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("100.00"), response.expectedCash());
        assertEquals(new BigDecimal("-5.00"), response.cashVariance());
        assertEquals("Five rupees short; recounted drawer", response.closingReason());
        assertNotNull(response.closedAt());
    }

    @Test
    void exactShiftCloseRetryReplaysCommittedClosureWithoutChangingTheCount() {
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("100.00"));
        shift.setClosingCashCount(new BigDecimal("95.00"));
        shift.setCashVariance(new BigDecimal("-5.00"));
        shift.setClosingReason("Drawer recounted");
        shift.setClosedByUsername("cashier.one");
        shift.setClosedAt(java.time.LocalDateTime.of(2026, 11, 21, 18, 0));
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        var response = service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("95.00"), "Drawer recounted"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("95.00"), response.closingCashCount());
        assertEquals(new BigDecimal("-5.00"), response.cashVariance());
        assertEquals("Drawer recounted", response.closingReason());
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
        verify(movementRepository, never()).findByShiftIdOrderByCreatedAtAscIdAsc(9L);
    }

    @Test
    void shiftCloseRetryRejectsDifferentClosingReason() {
        shift.setStatus("CLOSED");
        shift.setClosingCashCount(new BigDecimal("95.00"));
        shift.setClosingReason("Drawer recounted");
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        assertThrows(IllegalArgumentException.class, () -> service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("95.00"), "Different explanation"),
                "cashier.one",
                "CASHIER"
        ));
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
    }

    @Test
    void managerShiftHistoryReturnsPaginatedClosedCashReconciliationDetails() {
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("100.00"));
        shift.setClosingCashCount(new BigDecimal("95.00"));
        shift.setCashVariance(new BigDecimal("-5.00"));
        shift.setClosingReason("Drawer recounted");
        when(shiftRepository.findByRegisterLocationId(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shift), PageRequest.of(0, 25), 31));

        var history = service.getShiftHistory(1L, 0, 25);

        assertEquals(31, history.getTotalElements());
        assertEquals(2, history.getTotalPages());
        assertEquals(1, history.getContent().size());
        assertEquals(9L, history.getContent().get(0).id());
        assertEquals("CLOSED", history.getContent().get(0).status());
        assertEquals(new BigDecimal("100.00"), history.getContent().get(0).expectedCash());
        assertEquals(new BigDecimal("95.00"), history.getContent().get(0).closingCashCount());
        assertEquals(new BigDecimal("-5.00"), history.getContent().get(0).cashVariance());
        assertEquals("Drawer recounted", history.getContent().get(0).closingReason());
        verify(shiftRepository).findByRegisterLocationId(eq(1L), any(Pageable.class));
    }

    @Test
    void shiftHistoryRejectsInvalidLocationAndPageParameters() {
        assertThrows(IllegalArgumentException.class, () -> service.getShiftHistory(0L, 0, 25));
        assertThrows(IllegalArgumentException.class, () -> service.getShiftHistory(1L, -1, 25));
        assertThrows(IllegalArgumentException.class, () -> service.getShiftHistory(1L, 0, 101));
        verifyNoInteractions(shiftRepository);
    }

    @Test
    void managerCanProcessReturnAgainstAnOpenCashierShiftAtSameLocation() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        PosShift result = service.requireOpenShiftForManagerAction(
                9L, 1L, "manager.one", "MANAGER"
        );

        assertSame(shift, result);
    }

    @Test
    void cashierCannotProcessReturnAgainstAnotherCashiersShift() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        assertThrows(IllegalArgumentException.class, () ->
                service.requireOpenShiftForManagerAction(
                        9L, 1L, "cashier.two", "CASHIER"
                )
        );
    }

    @Test
    void dailyCashReconciliationRecalculatesDrawerTotalsFromTheMovementLedger() {
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("130.00"));
        shift.setClosingCashCount(new BigDecimal("125.00"));
        shift.setCashVariance(new BigDecimal("-5.00"));
        shift.setClosingReason("Short count investigated");
        when(locationRepository.findById(1L)).thenReturn(Optional.of(shift.getRegister().getLocation()));
        when(shiftRepository
                .findByRegisterLocationIdAndOpenedAtGreaterThanEqualAndOpenedAtLessThanOrderByOpenedAtAscIdAsc(
                        eq(1L), any(), any()))
                .thenReturn(List.of(shift));
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L)).thenReturn(List.of(
                movement("OPENING_FLOAT", "IN", "100.00", shift),
                movement("CASH_SALE", "IN", "40.00", shift),
                movement("CASH_OUT", "OUT", "10.00", shift)
        ));

        var result = service.getDailyCashReconciliation(
                1L, java.time.LocalDate.of(2026, 11, 22), "manager.one", "MANAGER"
        );

        assertEquals(1, result.shiftCount());
        assertEquals(0, result.openShiftCount());
        assertEquals(1, result.closedShiftCount());
        assertEquals(1, result.discrepancyCount());
        assertEquals(new BigDecimal("130.00"), result.expectedCashTotal());
        assertEquals(new BigDecimal("125.00"), result.countedCashTotal());
        assertEquals(new BigDecimal("-5.00"), result.varianceTotal());
        assertTrue(result.shifts().getFirst().ledgerConsistent());
        assertEquals(new BigDecimal("130.00"), result.shifts().getFirst().ledgerExpectedCash());
    }

    @Test
    void cashierCannotReadLocationWideShiftHistory() {
        assertThrows(IllegalArgumentException.class, () ->
                service.getShiftHistory(1L, 0, 25, "cashier.one", "CASHIER"));
        verifyNoInteractions(shiftRepository, locationRepository);
    }

    @Test
    void dailyCashReconciliationIsRestrictedToManagersAndAdministrators() {
        assertThrows(IllegalArgumentException.class, () -> service.getDailyCashReconciliation(
                1L, java.time.LocalDate.of(2026, 11, 22), "cashier.one", "CASHIER"
        ));
        verifyNoInteractions(locationRepository, shiftRepository, movementRepository);
    }

    @Test
    void supplierCashPaymentCreatesExactlyOneLinkedDrawerOutMovement() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("SUP-PAY-1"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L)).thenReturn(List.of(
                movement("OPENING_FLOAT", "IN", "100.00", shift),
                movement("CASH_SALE", "IN", "50.00", shift)
        ));

        service.recordSupplierPayment(
                9L, new BigDecimal("40.00"), "SUP-PAY-1",
                "admin.one", "ADMIN", 701L, 1L
        );

        var captor = org.mockito.ArgumentCaptor.forClass(PosCashMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());
        PosCashMovement saved = captor.getValue();
        assertEquals("SUPPLIER_PAYMENT", saved.getMovementType());
        assertEquals("OUT", saved.getDirection());
        assertEquals("SUPPLIER_PAYMENT", saved.getReferenceType());
        assertEquals(701L, saved.getReferenceId());
        assertEquals(new BigDecimal("40.00"), saved.getAmount());
    }

    @Test
    void supplierCashPaymentCannotOverdrawExpectedDrawerCash() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("SUP-PAY-2"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L)).thenReturn(List.of(
                movement("OPENING_FLOAT", "IN", "20.00", shift)
        ));

        assertThrows(IllegalArgumentException.class, () -> service.recordSupplierPayment(
                9L, new BigDecimal("30.00"), "SUP-PAY-2",
                "admin.one", "ADMIN", 702L, 1L
        ));
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    private PosCashMovement movement(String type, String direction, String amount, PosShift shift) {
        PosCashMovement movement = new PosCashMovement();
        movement.setShift(shift);
        movement.setMovementType(type);
        movement.setDirection(direction);
        movement.setAmount(new BigDecimal(amount));
        movement.setIdempotencyKey(type + "-" + amount);
        movement.setActorUsername("cashier.one");
        return movement;
    }

    @Test
    void cannotOpenSecondShiftOnRegisterWhileFirstShiftIsStillOpen() {
        when(shiftRepository.findByIdempotencyKey("SHIFT-OPEN-SECOND")).thenReturn(Optional.empty());
        when(registerRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(shift.getRegister()));
        when(shiftRepository.findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(3L, "OPEN"))
                .thenReturn(Optional.of(shift));

        assertThrows(IllegalArgumentException.class, () -> service.openShift(
                new nmk_backend.dto.PosShiftOpenRequest(
                        3L, new BigDecimal("50.00"), "SHIFT-OPEN-SECOND"
                ),
                "cashier.two"
        ));

        verify(registerRepository).findByIdForUpdate(3L);
        verify(shiftRepository).findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(3L, "OPEN");
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void inactiveRegisterCannotOpenANewShift() {
        shift.getRegister().setActive(false);
        when(shiftRepository.findByIdempotencyKey("SHIFT-OPEN-INACTIVE")).thenReturn(Optional.empty());
        when(registerRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(shift.getRegister()));

        assertThrows(IllegalArgumentException.class, () -> service.openShift(
                new nmk_backend.dto.PosShiftOpenRequest(
                        3L, new BigDecimal("50.00"), "SHIFT-OPEN-INACTIVE"
                ),
                "cashier.one"
        ));

        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void cashierCannotReadAnotherCashiersCurrentShift() {
        when(registerRepository.findById(3L)).thenReturn(Optional.of(shift.getRegister()));
        when(shiftRepository.findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(3L, "OPEN"))
                .thenReturn(Optional.of(shift));

        assertNull(service.getCurrentShift(3L, "cashier.two", "CASHIER"));
    }

    @Test
    void managerCanReadCurrentShiftOpenedByCashier() {
        when(registerRepository.findById(3L)).thenReturn(Optional.of(shift.getRegister()));
        when(shiftRepository.findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(3L, "OPEN"))
                .thenReturn(Optional.of(shift));
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L)).thenReturn(List.of());

        var response = service.getCurrentShift(3L, "manager.one", "MANAGER");

        assertNotNull(response);
        assertEquals("cashier.one", response.openedByUsername());
    }
}

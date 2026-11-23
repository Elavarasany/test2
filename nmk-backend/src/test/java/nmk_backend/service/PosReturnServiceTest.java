package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PosReturnServiceTest {

    private InventoryLocationRepository locationRepository;
    private PosSaleRepository saleRepository;
    private PosReturnRepository returnRepository;
    private PosReturnItemRepository returnItemRepository;
    private AdminRepository adminRepository;
    private InventoryService inventoryService;
    private PosShiftService shiftService;
    private PosReturnService service;

    private PosSale sale;
    private PosSaleItem line;
    private PosShift shift;
    private Admin manager;

    @BeforeEach
    void setUp() {
        locationRepository = mock(InventoryLocationRepository.class);
        saleRepository = mock(PosSaleRepository.class);
        returnRepository = mock(PosReturnRepository.class);
        returnItemRepository = mock(PosReturnItemRepository.class);
        adminRepository = mock(AdminRepository.class);
        inventoryService = mock(InventoryService.class);
        shiftService = mock(PosShiftService.class);
        service = new PosReturnService(
                locationRepository, saleRepository, returnRepository, returnItemRepository,
                adminRepository, inventoryService, shiftService, new ObjectMapper()
        );

        sale = new PosSale();
        ReflectionTestUtils.setField(sale, "id", 1L);
        sale.setInvoiceNumber("NMK-ORIGINAL-001");
        sale.setLocationId(1L);
        sale.setStatus("COMPLETED");
        sale.setSubtotal(new BigDecimal("200.00"));
        sale.setDiscountAmount(new BigDecimal("20.00"));
        sale.setGrandTotal(new BigDecimal("180.00"));

        line = new PosSaleItem();
        ReflectionTestUtils.setField(line, "id", 11L);
        line.setSale(sale);
        line.setVariantId(101L);
        line.setSku("FRAME-001");
        line.setProductName("Classic Frame");
        line.setVariantName("Black");
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("100.00"));
        line.setLineTotal(new BigDecimal("100.00"));
        sale.getItems().add(line);

        PosSaleItem second = new PosSaleItem();
        ReflectionTestUtils.setField(second, "id", 12L);
        second.setSale(sale);
        second.setVariantId(102L);
        second.setQuantity(1);
        second.setUnitPrice(new BigDecimal("100.00"));
        second.setLineTotal(new BigDecimal("100.00"));
        sale.getItems().add(second);

        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        PosRegister register = new PosRegister();
        ReflectionTestUtils.setField(register, "id", 3L);
        register.setLocation(location);
        shift = new PosShift();
        ReflectionTestUtils.setField(shift, "id", 99L);
        shift.setRegister(register);
        shift.setOpenedByUsername("manager.one");
        shift.setStatus("OPEN");

        manager = new Admin();
        manager.setId(7L);
        manager.setUsername("manager.one");

        when(saleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(saleRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sale));
        when(saleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(returnRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(returnRepository.findByIdempotencyKeyForUpdate(anyString())).thenReturn(Optional.empty());
        when(returnRepository.sumRefundTotalBySaleId(1L)).thenReturn(BigDecimal.ZERO);
        when(returnRepository.saveAndFlush(any(PosReturn.class))).thenAnswer(invocation -> {
            PosReturn record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 50L);
            record.setReturnNumber("RET-TEST-0001");
            return record;
        });
        when(returnItemRepository.sumReturnedQuantityBySaleItemId(11L)).thenReturn(0L);
        when(returnItemRepository.sumRefundedAmountBySaleItemId(11L)).thenReturn(BigDecimal.ZERO);
        when(adminRepository.findByUsernameIgnoreCase("manager.one")).thenReturn(Optional.of(manager));
        when(shiftService.requireOpenShiftForManagerAction(99L, 1L, "manager.one", "CASHIER")).thenReturn(shift);
    }

    @Test
    void calculatesRefundAfterProportionalSaleDiscountAndRestocksOnlyApprovedGoods() {
        PosReturnRequest request = new PosReturnRequest(
                99L,
                "Customer changed mind",
                "return-key-1",
                List.of(new PosReturnItemRequest(11L, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("90.00"), null))
        );

        PosReturnResponse response = service.processReturn(1L, request, "manager.one");

        assertEquals("90.00", response.refundTotal().toPlainString());
        assertEquals("RET-TEST-0001", response.returnNumber());
        assertEquals(1, response.items().size());
        assertEquals("RESTOCK", response.items().get(0).restockDisposition());

        verify(inventoryService).restockCustomerReturn(
                eq(1L), eq(101L), eq(1),
                eq("POS-RETURN-STOCK:return-key-1:11"),
                eq(7L), contains("RET-TEST-0001"), eq(50L)
        );
        verify(shiftService).recordCashRefund(
                same(shift), eq(new BigDecimal("90.00")),
                eq("POS-RETURN-CASH:return-key-1:0"), eq("manager.one"), eq(50L)
        );
    }

    @Test
    void rejectsReturningMoreThanTheRemainingSoldQuantity() {
        when(returnItemRepository.sumReturnedQuantityBySaleItemId(11L)).thenReturn(1L);

        PosReturnRequest request = new PosReturnRequest(
                99L, "Second return attempt", "return-key-2",
                List.of(new PosReturnItemRequest(11L, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("90.00"), null))
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.processReturn(1L, request, "manager.one"));
        verify(returnRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void rejectsRefundReferenceLongerThanDatabaseColumnBeforePersisting() {
        PosReturnRequest request = new PosReturnRequest(
                99L, "Customer changed mind", "return-key-long-ref",
                List.of(new PosReturnItemRequest(11L, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("UPI", new BigDecimal("90.00"), "R".repeat(151)))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.processReturn(1L, request, "manager.one"));

        assertTrue(exception.getMessage().contains("150 characters"));
        verify(returnRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void rejectsNonCashRefundWithoutProviderReference() {
        PosReturnRequest request = new PosReturnRequest(
                99L, "Customer changed mind", "return-key-3",
                List.of(new PosReturnItemRequest(11L, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("UPI", new BigDecimal("90.00"), null))
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.processReturn(1L, request, "manager.one"));
        verify(returnRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void voidsEntireSaleOnlyWithManagerApprovalAndRecordsFullRefund() {
        PosVoidRequest request = new PosVoidRequest(
                99L,
                "Sale cancelled before customer leaves",
                "void-key-1",
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("180.00"), null))
        );

        PosReturnResponse response = service.processVoid(1L, request, "manager.one");

        assertEquals("VOID", response.returnType());
        assertEquals("180.00", response.refundTotal().toPlainString());
        assertEquals(2, response.items().size());
        assertEquals(2, response.items().stream().mapToInt(PosReturnResponse.ItemResponse::quantity).sum());
        verify(inventoryService).restockCustomerReturn(
                eq(1L), eq(101L), eq(1),
                eq("POS-RETURN-STOCK:void-key-1:11"),
                eq(7L), contains("RET-TEST-0001"), eq(50L)
        );
        verify(inventoryService).restockCustomerReturn(
                eq(1L), eq(102L), eq(1),
                eq("POS-RETURN-STOCK:void-key-1:12"),
                eq(7L), contains("RET-TEST-0001"), eq(50L)
        );
        verify(shiftService).recordCashRefund(
                same(shift), eq(new BigDecimal("180.00")),
                eq("POS-RETURN-CASH:void-key-1:0"), eq("manager.one"), eq(50L)
        );
    }

    @Test
    void fullSaleVoidIsRejectedAfterAnyEarlierReturn() {
        when(returnRepository.findBySaleIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(new PosReturn()));

        PosVoidRequest request = new PosVoidRequest(
                99L, "Attempt void after return", "void-key-2",
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("180.00"), null))
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.processVoid(1L, request, "manager.one"));
        verify(returnRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void idempotencyKeyCannotBeReplayedByAnotherStaffAccount() {
        PosReturnRequest request = new PosReturnRequest(
                99L, "Customer changed mind", "return-key-actor",
                List.of(new PosReturnItemRequest(11L, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("90.00"), null))
        );
        String originalFingerprint = ReflectionTestUtils.invokeMethod(
                service, "fingerprint", 1L, request, "RETURN", "manager.one"
        );
        PosReturn existing = new PosReturn();
        existing.setIdempotencyKey("return-key-actor");
        existing.setRequestFingerprint(originalFingerprint);
        when(returnRepository.findByIdempotencyKey("return-key-actor"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.processReturn(1L, request, "manager.two"));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void idempotencyKeyCannotBeReusedAcrossDifferentOriginalSales() throws Exception {
        PosReturn existing = new PosReturn();
        existing.setIdempotencyKey("return-key-4");
        existing.setRequestFingerprint("fingerprint-for-another-sale");
        when(returnRepository.findByIdempotencyKey("return-key-4")).thenReturn(Optional.of(existing));

        PosReturnRequest request = new PosReturnRequest(
                99L, "Customer changed mind", "return-key-4",
                List.of(new PosReturnItemRequest(11L, 1, "RESTOCK", null)),
                List.of(new PosRefundPaymentRequest("CASH", new BigDecimal("90.00"), null))
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.processReturn(1L, request, "manager.one"));
        verifyNoInteractions(inventoryService);
    }


}

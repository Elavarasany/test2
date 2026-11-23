package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.SupplierBillCreateRequest;
import nmk_backend.dto.SupplierPaymentRequest;
import nmk_backend.dto.SupplierCreditNoteRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierAccountServiceTest {
    private SupplierRepository supplierRepository;
    private SupplierBillRepository billRepository;
    private SupplierPaymentRepository paymentRepository;
    private SupplierBillCreditNoteRepository creditNoteRepository;
    private SupplierReturnRepository supplierReturnRepository;
    private SupplierBillLandedCostAllocationRepository allocationRepository;
    private PurchaseOrderRepository purchaseOrderRepository;
    private GoodsReceiptRepository goodsReceiptRepository;
    private AdminRepository adminRepository;
    private PosShiftService posShiftService;
    private SupplierAccountService service;
    private Supplier supplier;
    private Admin admin;

    @BeforeEach
    void setUp() {
        supplierRepository = mock(SupplierRepository.class);
        billRepository = mock(SupplierBillRepository.class);
        paymentRepository = mock(SupplierPaymentRepository.class);
        creditNoteRepository = mock(SupplierBillCreditNoteRepository.class);
        supplierReturnRepository = mock(SupplierReturnRepository.class);
        allocationRepository = mock(SupplierBillLandedCostAllocationRepository.class);
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        adminRepository = mock(AdminRepository.class);
        posShiftService = mock(PosShiftService.class);
        service = new SupplierAccountService(
                supplierRepository, billRepository, paymentRepository, creditNoteRepository, supplierReturnRepository,
                allocationRepository, purchaseOrderRepository, goodsReceiptRepository, adminRepository, posShiftService,
                new ObjectMapper().findAndRegisterModules()
        );

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 7L);
        supplier.setName("Lens Supplier");
        supplier.setActive(true);

        admin = new Admin();
        admin.setUsername("admin.one");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        when(adminRepository.findByUsernameIgnoreCase("admin.one")).thenReturn(Optional.of(admin));
    }

    @Test
    void partialSupplierPaymentReducesBillBalanceWithoutExceedingIt() {
        SupplierBill bill = bill(12L, new BigDecimal("100.00"));
        when(paymentRepository.findByIdempotencyKey("PAY-12")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(bill));
        bill.setPaidAmount(new BigDecimal("20.00"));
        when(paymentRepository.saveAndFlush(any(SupplierPayment.class))).thenAnswer(invocation -> {
            SupplierPayment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 88L);
            return payment;
        });

        var result = service.recordPayment(
                12L,
                new SupplierPaymentRequest(new BigDecimal("30.00"), "BANK", "UTR-7788", null, "PAY-12"),
                "admin.one",
                "ADMIN"
        );

        assertEquals(88L, result.id());
        assertEquals(new BigDecimal("30.00"), result.amount());
        assertEquals("CONFIRMED", result.status());
        assertEquals("BANK", result.paymentMethod());
        verify(paymentRepository).saveAndFlush(any(SupplierPayment.class));
        verifyNoInteractions(posShiftService);
    }

    @Test
    void cashSupplierPaymentIsLinkedToTheCorrectPurchaseOrderLocationShift() {
        SupplierBill bill = bill(14L, new BigDecimal("100.00"));
        PurchaseOrder order = new PurchaseOrder();
        InventoryLocation location = new InventoryLocation();
        location.setId(4L);
        order.setLocation(location);
        bill.setPurchaseOrder(order);
        when(paymentRepository.findByIdempotencyKey("PAY-CASH-14")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(bill));
        bill.setPaidAmount(BigDecimal.ZERO);
        when(paymentRepository.saveAndFlush(any(SupplierPayment.class))).thenAnswer(invocation -> {
            SupplierPayment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 89L);
            return payment;
        });

        var result = service.recordPayment(
                14L,
                new SupplierPaymentRequest(new BigDecimal("25.00"), "CASH", null, 55L, "PAY-CASH-14"),
                "admin.one",
                "ADMIN"
        );

        assertEquals("CASH", result.paymentMethod());
        assertEquals(55L, result.shiftId());
        verify(posShiftService).recordSupplierPayment(
                55L, new BigDecimal("25.00"), "PAY-CASH-14",
                "admin.one", "ADMIN", 89L, 4L
        );
    }

    @Test
    void landedUnitCostIncludesAllPostedLandedCostAllocationsForAReceiptLine() {
        SupplierBill bill = bill(15L, new BigDecimal("30.00"));
        bill.setBillType("LANDED_COST");
        GoodsReceiptItem item = receiptItem(101L, 2, "100.00");
        PurchaseOrderItem orderItem = new PurchaseOrderItem();
        ReflectionTestUtils.setField(orderItem, "id", 201L);
        ProductVariant variant = new ProductVariant();
        variant.setId(301L);
        variant.setSku("NMK-SKU-301");
        orderItem.setVariant(variant);
        item.setPurchaseOrderItem(orderItem);

        SupplierBillLandedCostAllocation row = allocation(51L, item, new BigDecimal("30.00"));
        row.setBill(bill);
        when(billRepository.findById(15L)).thenReturn(Optional.of(bill));
        when(allocationRepository.findByBillIdOrderByIdAsc(15L)).thenReturn(List.of(row));
        when(allocationRepository.sumAllocatedAmountByGoodsReceiptItemId(101L))
                .thenReturn(new BigDecimal("50.00"));

        var result = service.getLandedCostAllocations(15L, "admin.one");

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("125.00"), result.getFirst().landedUnitCost());
        verify(allocationRepository).sumAllocatedAmountByGoodsReceiptItemId(101L);
    }

    @Test
    void payablesAgingUsesPaymentsCreditsAndDueDateBuckets() {
        LocalDate asOf = LocalDate.of(2026, 11, 22);
        SupplierBill dueWithin30 = bill(21L, new BigDecimal("100.00"));
        dueWithin30.setPaidAmount(new BigDecimal("20.00"));
        dueWithin30.setCreditedAmount(new BigDecimal("10.00"));
        dueWithin30.setDueDate(asOf.minusDays(10));

        SupplierBill dueWithin60 = bill(22L, new BigDecimal("50.00"));
        dueWithin60.setDueDate(asOf.minusDays(45));

        SupplierBill dueOver90 = bill(23L, new BigDecimal("30.00"));
        dueOver90.setDueDate(asOf.minusDays(100));

        SupplierBill noDueDate = bill(24L, new BigDecimal("40.00"));
        SupplierBill paid = bill(25L, new BigDecimal("20.00"));
        paid.setPaidAmount(new BigDecimal("20.00"));
        paid.setDueDate(asOf.minusDays(200));

        when(supplierRepository.existsById(7L)).thenReturn(true);
        when(billRepository.findBySupplierIdOrderByInvoiceDateDescCreatedAtDesc(eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(dueWithin30, dueWithin60, dueOver90, noDueDate, paid),
                        PageRequest.of(0, 100), 5));
        when(paymentRepository.sumConfirmedAmountByBillId(21L)).thenReturn(new BigDecimal("20.00"));
        when(paymentRepository.sumConfirmedAmountByBillId(22L)).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumConfirmedAmountByBillId(23L)).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumConfirmedAmountByBillId(24L)).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumConfirmedAmountByBillId(25L)).thenReturn(new BigDecimal("20.00"));

        var result = service.getPayablesAging(7L, asOf, "admin.one");

        assertEquals(4, result.openBillCount());
        assertEquals(3, result.overdueBillCount());
        assertEquals(new BigDecimal("40.00"), result.notDue());
        assertEquals(new BigDecimal("70.00"), result.overdue1To30Days());
        assertEquals(new BigDecimal("50.00"), result.overdue31To60Days());
        assertEquals(new BigDecimal("0.00"), result.overdue61To90Days());
        assertEquals(new BigDecimal("30.00"), result.overdueOver90Days());
        assertEquals(new BigDecimal("190.00"), result.totalOutstanding());
    }

    @Test
    void supplierCreditNoteReducesOutstandingPayableAndIsIdempotentlyRecorded() {
        SupplierBill bill = bill(16L, new BigDecimal("100.00"));
        bill.setPaidAmount(new BigDecimal("20.00"));
        when(creditNoteRepository.findByIdempotencyKey("CN-16")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(bill));
        when(creditNoteRepository.existsBySupplierIdAndCreditNoteNumberIgnoreCase(7L, "CN-2026-16"))
                .thenReturn(false);
        when(creditNoteRepository.saveAndFlush(any(SupplierBillCreditNote.class))).thenAnswer(invocation -> {
            SupplierBillCreditNote note = invocation.getArgument(0);
            ReflectionTestUtils.setField(note, "id", 116L);
            return note;
        });

        var result = service.recordCreditNote(
                16L,
                new SupplierCreditNoteRequest("CN-2026-16", LocalDate.of(2026, 11, 22),
                        new BigDecimal("15.00"), "Supplier issued a price adjustment", null, "CN-16"),
                "admin.one"
        );

        assertEquals(116L, result.id());
        assertEquals(new BigDecimal("15.00"), result.amount());
        assertEquals(new BigDecimal("15.00"), bill.getCreditedAmount());
        verify(billRepository).saveAndFlush(bill);
    }

    @Test
    void supplierCreditNoteCannotExceedUnpaidBillBalance() {
        SupplierBill bill = bill(17L, new BigDecimal("100.00"));
        bill.setPaidAmount(new BigDecimal("80.00"));
        when(creditNoteRepository.findByIdempotencyKey("CN-17")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(bill));
        when(creditNoteRepository.existsBySupplierIdAndCreditNoteNumberIgnoreCase(7L, "CN-2026-17"))
                .thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.recordCreditNote(
                17L,
                new SupplierCreditNoteRequest("CN-2026-17", LocalDate.of(2026, 11, 22),
                        new BigDecimal("25.00"), "Return credit", null, "CN-17"),
                "admin.one"
        ));
        verify(creditNoteRepository, never()).saveAndFlush(any(SupplierBillCreditNote.class));
        verify(billRepository, never()).saveAndFlush(any(SupplierBill.class));
    }

    @Test
    void supplierPaymentCannotExceedOutstandingBillBalance() {
        SupplierBill bill = bill(13L, new BigDecimal("100.00"));
        when(paymentRepository.findByIdempotencyKey("PAY-13")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(bill));
        bill.setPaidAmount(new BigDecimal("80.00"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                service.recordPayment(
                        13L,
                        new SupplierPaymentRequest(new BigDecimal("30.00"), "UPI", "UPI-REF-13", null, "PAY-13"),
                        "admin.one",
                        "ADMIN"
                )
        );

        assertTrue(exception.getMessage().contains("exceeds the outstanding"));
        verify(paymentRepository, never()).saveAndFlush(any(SupplierPayment.class));
        verifyNoInteractions(posShiftService);
    }

    @Test
    void landedCostBillAllocatesItsAmountProRataAcrossReceivedGoodsValue() {
        PurchaseOrder order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", 31L);
        ReflectionTestUtils.setField(order, "orderNumber", "PO-31");
        order.setSupplier(supplier);
        order.setStatus("RECEIVED");

        GoodsReceipt receipt = new GoodsReceipt();
        GoodsReceiptItem first = receiptItem(101L, 2, "100.00");
        GoodsReceiptItem second = receiptItem(102L, 1, "100.00");
        receipt.getItems().addAll(List.of(first, second));

        when(supplierRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(supplier));
        when(billRepository.findByIdempotencyKey("LC-31")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.existsBySupplierIdAndInvoiceNumberIgnoreCase(7L, "FRT-31")).thenReturn(false);
        when(purchaseOrderRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(order));
        when(goodsReceiptRepository.findByPurchaseOrderIdOrderByCreatedAtDesc(31L)).thenReturn(List.of(receipt));
        when(billRepository.saveAndFlush(any(SupplierBill.class))).thenAnswer(invocation -> {
            SupplierBill bill = invocation.getArgument(0);
            ReflectionTestUtils.setField(bill, "id", 41L);
            return bill;
        });
        when(allocationRepository.save(any(SupplierBillLandedCostAllocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepository.sumAllocatedAmountByBillId(41L)).thenReturn(new BigDecimal("30.00"));
        when(allocationRepository.findByBillIdOrderByIdAsc(41L)).thenAnswer(invocation ->
                List.of(
                        allocation(41L, first, new BigDecimal("20.00")),
                        allocation(42L, second, new BigDecimal("10.00"))
                ));
        when(paymentRepository.sumConfirmedAmountByBillId(41L)).thenReturn(BigDecimal.ZERO);

        var result = service.createBill(
                new SupplierBillCreateRequest(
                        7L, 31L, "FRT-31", LocalDate.of(2026, 11, 22),
                        LocalDate.of(2026, 12, 22), new BigDecimal("30.00"),
                        "LANDED_COST", "LC-31"
                ),
                "admin.one"
        );

        assertEquals("LANDED_COST", result.billType());
        assertEquals(2, result.landedCostAllocationCount());
        assertEquals(new BigDecimal("30.00"), result.landedCostAllocatedAmount());
        var captor = org.mockito.ArgumentCaptor.forClass(SupplierBillLandedCostAllocation.class);
        verify(allocationRepository, times(2)).save(captor.capture());
        assertEquals(new BigDecimal("20.00"), captor.getAllValues().get(0).getAllocatedAmount());
        assertEquals(new BigDecimal("10.00"), captor.getAllValues().get(1).getAllocatedAmount());
    }

    private SupplierBill bill(Long id, BigDecimal amount) {
        SupplierBill bill = new SupplierBill();
        ReflectionTestUtils.setField(bill, "id", id);
        bill.setSupplier(supplier);
        bill.setAmount(amount);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setCreditedAmount(BigDecimal.ZERO);
        bill.setStatus("POSTED");
        bill.setBillType("GOODS");
        return bill;
    }

    private GoodsReceiptItem receiptItem(Long id, int quantity, String unitCost) {
        GoodsReceiptItem item = new GoodsReceiptItem();
        ReflectionTestUtils.setField(item, "id", id);
        item.setQuantity(quantity);
        item.setUnitCost(new BigDecimal(unitCost));
        return item;
    }

    private SupplierBillLandedCostAllocation allocation(Long id, GoodsReceiptItem item, BigDecimal amount) {
        SupplierBillLandedCostAllocation allocation = new SupplierBillLandedCostAllocation();
        ReflectionTestUtils.setField(allocation, "id", id);
        allocation.setGoodsReceiptItem(item);
        allocation.setAllocatedAmount(amount);
        return allocation;
    }
}

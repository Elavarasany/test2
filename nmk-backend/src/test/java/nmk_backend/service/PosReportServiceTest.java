package nmk_backend.service;

import nmk_backend.entity.*;
import jakarta.persistence.EntityManager;
import nmk_backend.dto.PosInventoryReportResponse;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PosReportServiceTest {

    private PosSaleRepository saleRepository;
    private PosReturnRepository returnRepository;
    private InventoryLocationRepository locationRepository;
    private PosExchangeCreditSettlementRepository exchangeCreditRepository;
    private InventoryBalanceRepository inventoryBalanceRepository;
    private PosReportService service;

    @BeforeEach
    void setUp() {
        saleRepository = mock(PosSaleRepository.class);
        returnRepository = mock(PosReturnRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        exchangeCreditRepository = mock(PosExchangeCreditSettlementRepository.class);
        inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        service = new PosReportService(saleRepository, returnRepository, locationRepository, exchangeCreditRepository, inventoryBalanceRepository);
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        when(exchangeCreditRepository.sumExchangeCreditApplied(eq(1L), any(), any())).thenReturn(new BigDecimal("0.00"));

        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setName("Main Store");
        when(locationRepository.findById(1L)).thenReturn(Optional.of(location));
    }

    @Test
    void reportCombinesSalesDiscountsTenderReturnsAndTopProducts() {
        LocalDate day = LocalDate.of(2026, 11, 21);
        PosSale sale = new PosSale();
        sale.setLocationId(1L);
        sale.setCashierUsername("cashier.one");
        sale.setStatus("COMPLETED");
        sale.setSubtotal(new BigDecimal("1000.00"));
        sale.setDiscountAmount(new BigDecimal("100.00"));
        sale.setGrandTotal(new BigDecimal("900.00"));
        ReflectionTestUtils.setField(sale, "createdAt", day.atTime(10, 30));

        PosPayment cash = new PosPayment();
        cash.setPaymentMethod("CASH");
        cash.setAmount(new BigDecimal("500.00"));
        sale.getPayments().add(cash);
        PosPayment upi = new PosPayment();
        upi.setPaymentMethod("UPI");
        upi.setAmount(new BigDecimal("400.00"));
        sale.getPayments().add(upi);

        PosSaleItem item = new PosSaleItem();
        item.setProductId(15L);
        item.setProductName("Classic Frame");
        item.setSku("FRAME-015");
        item.setQuantity(2);
        item.setLineTotal(new BigDecimal("1000.00"));
        sale.getItems().add(item);

        // A second variant of the same product must roll up to the product total;
        // the report must not display an arbitrary single-variant SKU.
        PosSaleItem secondVariant = new PosSaleItem();
        secondVariant.setProductId(15L);
        secondVariant.setProductName("Classic Frame");
        secondVariant.setSku("FRAME-015-BLACK");
        secondVariant.setQuantity(1);
        secondVariant.setLineTotal(new BigDecimal("300.00"));
        sale.getItems().add(secondVariant);

        PosReturn returned = new PosReturn();
        returned.setSale(sale);
        returned.setStatus("COMPLETED");
        returned.setReturnType("RETURN");
        returned.setRefundTotal(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(returned, "createdAt", day.atTime(15, 45));
        PosRefundPayment refund = new PosRefundPayment();
        refund.setPaymentMethod("UPI");
        refund.setAmount(new BigDecimal("200.00"));
        returned.getRefundPayments().add(refund);

        PosReturn voided = new PosReturn();
        voided.setSale(sale);
        voided.setStatus("COMPLETED");
        voided.setReturnType("VOID");
        voided.setRefundTotal(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(voided, "createdAt", day.atTime(16, 15));
        PosRefundPayment voidRefund = new PosRefundPayment();
        voidRefund.setPaymentMethod("CASH");
        voidRefund.setAmount(new BigDecimal("100.00"));
        voided.getRefundPayments().add(voidRefund);

        when(saleRepository.findCompletedSalesForReport(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sale)));
        when(returnRepository.findCompletedReturnsForReport(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(returned, voided)));
        // Exchange credit is a non-cash settlement component, not a provider/cash refund.
        when(exchangeCreditRepository.sumExchangeCreditApplied(eq(1L), any(), any()))
                .thenReturn(new BigDecimal("75.00"));

        var report = service.getSalesReport(1L, day, day);

        assertEquals(1, report.transactionCount());
        assertEquals(new BigDecimal("1000.00"), report.subtotal());
        assertEquals(new BigDecimal("100.00"), report.discounts());
        assertEquals(new BigDecimal("900.00"), report.netSalesBeforeRefunds());
        assertEquals(1, report.returnCount(), "Full voids must not count as customer returns");
        assertEquals(new BigDecimal("300.00"), report.refunds());
        assertEquals(new BigDecimal("100.00"), report.salesReversals(),
                "Sales reversals must include full voids only, not ordinary customer returns");
        assertEquals(new BigDecimal("300.00"), report.recordedRefundPayments());
        assertEquals(new BigDecimal("75.00"), report.exchangeCreditApplied(),
                "Exchange credit must be reported separately from actual refund tenders");
        assertEquals(new BigDecimal("300.00"), report.recordedRefundPayments(),
                "Exchange credit must not inflate cash/UPI/card refund payment totals");
        assertEquals(new BigDecimal("600.00"), report.netSalesAfterRefunds());
        assertEquals(new BigDecimal("500.00"), report.salesByPaymentMethod().get("CASH"));
        assertEquals(new BigDecimal("400.00"), report.salesByPaymentMethod().get("UPI"));
        assertEquals(new BigDecimal("200.00"), report.refundsByPaymentMethod().get("UPI"));
        assertEquals(new BigDecimal("100.00"), report.refundsByPaymentMethod().get("CASH"));
        assertEquals("cashier.one", report.cashiers().get(0).cashierUsername());
        assertEquals(3L, report.topProducts().get(0).quantitySold());
        assertEquals(new BigDecimal("1300.00"), report.topProducts().get(0).grossLineTotal());
        assertEquals(new BigDecimal("600.00"), report.daily().get(0).netAfterRefunds());
        assertEquals(1, report.daily().get(0).returnCount(), "Voids must not be counted as customer returns");
    }

    @Test
    void reportGroupsRefundsByProcessingDateEvenWhenOriginalSaleWasEarlier() {
        LocalDate saleDay = LocalDate.of(2026, 11, 20);
        LocalDate refundDay = LocalDate.of(2026, 11, 21);

        PosSale originalSale = new PosSale();
        originalSale.setLocationId(1L);
        originalSale.setCashierUsername("cashier.one");
        originalSale.setStatus("COMPLETED");
        originalSale.setSubtotal(new BigDecimal("500.00"));
        originalSale.setDiscountAmount(new BigDecimal("0.00"));
        originalSale.setGrandTotal(new BigDecimal("500.00"));
        ReflectionTestUtils.setField(originalSale, "createdAt", saleDay.atTime(11, 0));

        PosReturn processedRefund = new PosReturn();
        processedRefund.setSale(originalSale);
        processedRefund.setStatus("COMPLETED");
        processedRefund.setReturnType("RETURN");
        processedRefund.setRefundTotal(new BigDecimal("125.00"));
        ReflectionTestUtils.setField(processedRefund, "createdAt", refundDay.atTime(14, 30));

        when(saleRepository.findCompletedSalesForReport(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(returnRepository.findCompletedReturnsForReport(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(processedRefund)));

        var report = service.getSalesReport(1L, refundDay, refundDay);

        assertEquals(0, report.transactionCount(),
                "The original sale is outside the selected processing date");
        assertEquals(1, report.returnCount());
        assertEquals(new BigDecimal("125.00"), report.refunds());
        assertEquals(new BigDecimal("-125.00"), report.netSalesAfterRefunds(),
                "A refund processed today remains visible even when its original sale was earlier");
        assertEquals(refundDay, report.daily().get(0).date());
        assertEquals(new BigDecimal("125.00"), report.daily().get(0).refunds());
    }

    @Test
    void salesReportReadsAllMatchingSalesAcrossBoundedPages() {
        LocalDate day = LocalDate.of(2026, 11, 21);
        List<PosSale> firstBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            PosSale sale = new PosSale();
            sale.setLocationId(1L);
            sale.setCashierUsername("cashier.one");
            sale.setStatus("COMPLETED");
            sale.setSubtotal(new BigDecimal("10.00"));
            sale.setDiscountAmount(new BigDecimal("1.00"));
            sale.setGrandTotal(new BigDecimal("9.00"));
            ReflectionTestUtils.setField(sale, "createdAt", day.atStartOfDay().plusMinutes(i));
            firstBatch.add(sale);
        }
        PosSale last = new PosSale();
        last.setLocationId(1L);
        last.setCashierUsername("cashier.two");
        last.setStatus("COMPLETED");
        last.setSubtotal(new BigDecimal("20.00"));
        last.setDiscountAmount(new BigDecimal("2.00"));
        last.setGrandTotal(new BigDecimal("18.00"));
        ReflectionTestUtils.setField(last, "createdAt", day.atTime(23, 59));

        when(saleRepository.findCompletedSalesForReport(eq(1L), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    return pageable.getPageNumber() == 0
                            ? new PageImpl<>(firstBatch, pageable, 201)
                            : new PageImpl<>(List.of(last), pageable, 201);
                });
        when(returnRepository.findCompletedReturnsForReport(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var report = service.getSalesReport(1L, day, day);

        assertEquals(201, report.transactionCount());
        assertEquals(new BigDecimal("2020.00"), report.subtotal());
        assertEquals(new BigDecimal("202.00"), report.discounts());
        assertEquals(new BigDecimal("1818.00"), report.netSalesBeforeRefunds());
        assertEquals(2, report.cashiers().size());
        verify(saleRepository, times(2))
                .findCompletedSalesForReport(eq(1L), any(), any(), any(Pageable.class));
        verify((EntityManager) ReflectionTestUtils.getField(service, "entityManager"), times(3)).clear();
    }

    @Test
    void inventoryReportShowsOnHandReservedAvailableAndLowStockWithoutInventingValuation() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L); location.setCode("MAIN"); location.setName("Main Store");
        Product product = new Product(); product.setId(10L); product.setName("Classic Frame");
        ProductVariant variant = new ProductVariant(); variant.setId(20L); variant.setProduct(product);
        variant.setSku("FRAME-20"); variant.setVariantName("Black"); variant.setAttributes(java.util.Map.of("color","Black"));
        InventoryBalance balance = new InventoryBalance(); balance.setLocation(location); balance.setVariant(variant);
        balance.setOnHandQuantity(8); balance.setReservedQuantity(2); balance.setReorderLevel(7);
        when(inventoryBalanceRepository.findByLocationId(eq(1L), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(balance), invocation.getArgument(1), 1));

        var report = service.getInventoryReport(1L);
        assertEquals(1, report.variantCount());
        assertEquals(8L, report.onHandQuantity());
        assertEquals(2L, report.reservedQuantity());
        assertEquals(6L, report.availableQuantity());
        assertEquals(1, report.lowStockCount());
        assertTrue(report.items().get(0).lowStock());
        assertEquals(6, report.items().get(0).availableQuantity());
        assertEquals(1, report.totalItems());
        assertEquals(0, report.itemsPage());
        assertFalse(report.itemsHasNext());
    }

    @Test
    void inventoryReportProcessesEveryBalanceAcrossPagesAndPreservesTotals() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("MAIN");
        location.setName("Main Store");
        when(locationRepository.findById(1L)).thenReturn(Optional.of(location));

        List<InventoryBalance> firstBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Product product = new Product();
            product.setId((long) i + 1);
            product.setName("Frame " + i);
            ProductVariant variant = new ProductVariant();
            variant.setId((long) i + 1);
            variant.setProduct(product);
            variant.setSku(String.format("SKU-%03d", i));
            variant.setAttributes(java.util.Map.of("color", "Black"));
            InventoryBalance balance = new InventoryBalance();
            balance.setLocation(location);
            balance.setVariant(variant);
            balance.setOnHandQuantity(10);
            balance.setReservedQuantity(2);
            balance.setReorderLevel(7);
            firstBatch.add(balance);
        }
        Product lastProduct = new Product();
        lastProduct.setId(999L);
        lastProduct.setName("Last Frame");
        ProductVariant lastVariant = new ProductVariant();
        lastVariant.setId(999L);
        lastVariant.setProduct(lastProduct);
        lastVariant.setSku("SKU-LAST");
        lastVariant.setAttributes(java.util.Map.of());
        InventoryBalance lastBalance = new InventoryBalance();
        lastBalance.setLocation(location);
        lastBalance.setVariant(lastVariant);
        lastBalance.setOnHandQuantity(5);
        lastBalance.setReservedQuantity(1);
        lastBalance.setReorderLevel(5);

        when(inventoryBalanceRepository.findByLocationId(eq(1L), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return pageable.getPageNumber() == 0
                            ? new PageImpl<>(firstBatch, pageable, 201)
                            : new PageImpl<>(List.of(lastBalance), pageable, 201);
                });

        var report = service.getInventoryReport(1L);

        assertEquals(201, report.variantCount());
        assertEquals(2005L, report.onHandQuantity());
        assertEquals(401L, report.reservedQuantity());
        assertEquals(1604L, report.availableQuantity());
        assertEquals(1, report.lowStockCount());
        assertEquals(201, report.totalItems());
        assertEquals(50, report.items().size());
        assertEquals(50, report.itemsSize());
        assertTrue(report.itemsHasNext());
        assertEquals("Frame 0", report.items().get(0).productName());
        assertEquals("Frame 49", report.items().get(report.items().size() - 1).productName());
        verify(inventoryBalanceRepository, times(2))
                .findByLocationId(eq(1L), any(Pageable.class));
    }

    @Test
    void inventoryReportEntriesCanBeLoadedInValidatedPages() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("MAIN");
        location.setName("Main Store");
        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        ProductVariant variant = new ProductVariant();
        variant.setId(20L);
        variant.setProduct(product);
        variant.setSku("FRAME-20");
        variant.setAttributes(java.util.Map.of());
        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(2);
        balance.setReorderLevel(7);
        when(inventoryBalanceRepository.findByLocationId(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(balance)));

        var page = service.getInventoryReportEntries(1L, 0, 25, "admin", "ADMIN");

        assertEquals(1, page.getTotalElements());
        assertEquals("Classic Frame", page.getContent().get(0).productName());
        assertThrows(IllegalArgumentException.class,
                () -> service.getInventoryReportEntries(1L, -1, 25, "admin", "ADMIN"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getInventoryReportEntries(1L, 0, 101, "admin", "ADMIN"));
    }

    @Test
    void rejectsInvalidOrExcessiveReportDateRanges() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.getSalesReport(1L, start, start.minusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.getSalesReport(1L, start, start.plusDays(368)));
        verifyNoInteractions(saleRepository, returnRepository);
    }
}
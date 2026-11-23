package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcurementServiceTest {

    private SupplierRepository supplierRepository;
    private PurchaseOrderRepository purchaseOrderRepository;
    private PurchaseOrderItemRepository purchaseOrderItemRepository;
    private GoodsReceiptRepository goodsReceiptRepository;
    private SupplierReturnRepository supplierReturnRepository;
    private InventoryLocationRepository locationRepository;
    private ProductVariantRepository variantRepository;
    private AdminRepository adminRepository;
    private InventoryService inventoryService;
    private ProcurementService service;

    private Supplier supplier;
    private InventoryLocation location;
    private ProductVariant variant;
    private Admin manager;
    private PurchaseOrder order;
    private PurchaseOrderItem orderItem;

    @BeforeEach
    void setUp() {
        supplierRepository = mock(SupplierRepository.class);
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        purchaseOrderItemRepository = mock(PurchaseOrderItemRepository.class);
        goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        supplierReturnRepository = mock(SupplierReturnRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        adminRepository = mock(AdminRepository.class);
        inventoryService = mock(InventoryService.class);

        service = new ProcurementService(
                supplierRepository, purchaseOrderRepository, purchaseOrderItemRepository,
                goodsReceiptRepository, supplierReturnRepository, locationRepository,
                variantRepository, adminRepository, inventoryService, new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 2L);
        supplier.setName("Optical Frames Supplier");
        supplier.setActive(true);

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
        variant.setActive(true);

        manager = new Admin();
        manager.setId(7L);
        manager.setUsername("manager.one");

        order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", 20L);
        ReflectionTestUtils.setField(order, "orderNumber", "PO-TEST-002");
        order.setSupplier(supplier);
        order.setLocation(location);
        order.setStatus("ORDERED");
        order.setCreatedByUsername("manager.one");

        orderItem = new PurchaseOrderItem();
        ReflectionTestUtils.setField(orderItem, "id", 200L);
        orderItem.setPurchaseOrder(order);
        orderItem.setVariant(variant);
        orderItem.setOrderedQuantity(10);
        orderItem.setReceivedQuantity(0);
        orderItem.setReturnedQuantity(0);
        orderItem.setUnitCost(new BigDecimal("250.00"));
        orderItem.setLineTotal(new BigDecimal("2500.00"));
        order.getItems().add(orderItem);

        when(adminRepository.findByUsernameIgnoreCase("manager.one")).thenReturn(Optional.of(manager));
    }

    @Test
    void createSupplierTranslatesConcurrentUniqueConstraintViolation() {
        when(supplierRepository.existsByNameIgnoreCase("New Supplier")).thenReturn(false);
        when(supplierRepository.saveAndFlush(any(Supplier.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key", new java.sql.SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'uk_suppliers_name'", "23000", 1062)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createSupplier(new SupplierCreateRequest(
                        " New Supplier ", null, null, null, null)));

        assertEquals("A supplier with this name already exists", error.getMessage());
        verify(supplierRepository).saveAndFlush(any(Supplier.class));
    }

    @Test
    void createSupplierDoesNotMisreportAnotherUniqueIndexViolationAsDuplicateName() {
        when(supplierRepository.existsByNameIgnoreCase("New Supplier")).thenReturn(false);
        org.springframework.dao.DataIntegrityViolationException databaseFailure =
                new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key", new java.sql.SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'uk_suppliers_email'", "23000", 1062));
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenThrow(databaseFailure);

        org.springframework.dao.DataIntegrityViolationException thrown = assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.createSupplier(new SupplierCreateRequest(
                        "New Supplier", null, null, null, null)));

        assertSame(databaseFailure, thrown);
    }

    @Test
    void createSupplierDoesNotMisreportOtherDatabaseConstraintFailuresAsDuplicateNames() {
        when(supplierRepository.existsByNameIgnoreCase("New Supplier")).thenReturn(false);
        org.springframework.dao.DataIntegrityViolationException databaseFailure =
                new org.springframework.dao.DataIntegrityViolationException("foreign key failure");
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenThrow(databaseFailure);

        org.springframework.dao.DataIntegrityViolationException thrown = assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.createSupplier(new SupplierCreateRequest(
                        "New Supplier", null, null, null, null)));

        assertSame(databaseFailure, thrown);
    }

    @Test
    void createPurchaseOrderStoresSupplierCostAndSubtotalWithoutChangingStock() {
        AtomicReference<PurchaseOrder> savedOrder = new AtomicReference<>();
        when(purchaseOrderRepository.findByIdempotencyKey("po-create-key-1"))
                .thenAnswer(invocation -> Optional.ofNullable(savedOrder.get()));
        when(supplierRepository.findById(2L)).thenReturn(Optional.of(supplier));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(variantRepository.findById(5L)).thenReturn(Optional.of(variant));
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 21L);
            ReflectionTestUtils.setField(saved, "orderNumber", "PO-TEST-NEW");
            savedOrder.set(saved);
            return saved;
        });

        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest(
                2L, 1L, "Initial purchase",
                List.of(new PurchaseOrderItemRequest(5L, 4, new BigDecimal("250.00"))),
                "po-create-key-1"
        );
        var response = service.createPurchaseOrder(request, "manager.one");
        var replay = service.createPurchaseOrder(request, "manager.one");
        Admin otherManager = new Admin();
        otherManager.setId(8L);
        otherManager.setUsername("manager.two");
        when(adminRepository.findByUsernameIgnoreCase("manager.two")).thenReturn(Optional.of(otherManager));
        assertThrows(IllegalArgumentException.class,
                () -> service.createPurchaseOrder(request, "manager.two"));

        assertEquals("DRAFT", response.status());
        assertEquals(new BigDecimal("1000.00"), response.subtotal());
        assertEquals(4, response.items().get(0).orderedQuantity());
        assertEquals(response.id(), replay.id());
        verify(purchaseOrderRepository, times(1)).saveAndFlush(any(PurchaseOrder.class));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void purchaseOrderSubmissionRetryIsSafeAfterUncertainResponse() {
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));

        PurchaseOrderResponse response = service.submitPurchaseOrder(20L);

        assertEquals("ORDERED", response.status());
        assertEquals("ORDERED", order.getStatus());
        verify(purchaseOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void goodsReceiptAddsInventoryAndTracksPartialDelivery() {
        when(goodsReceiptRepository.findByIdempotencyKey("gr-key-1")).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));
        when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class))).thenAnswer(invocation -> {
            GoodsReceipt receipt = invocation.getArgument(0);
            ReflectionTestUtils.setField(receipt, "id", 70L);
            ReflectionTestUtils.setField(receipt, "receiptNumber", "GR-TEST-070");
            return receipt;
        });
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GoodsReceiptResponse response = service.receiveGoods(
                20L,
                new GoodsReceiptRequest(
                        "Supplier delivery received and checked",
                        "gr-key-1",
                        List.of(new GoodsReceiptItemRequest(200L, 4))
                ),
                "manager.one"
        );

        assertEquals("GR-TEST-070", response.receiptNumber());
        assertEquals(4, orderItem.getReceivedQuantity());
        assertEquals("PARTIALLY_RECEIVED", order.getStatus());
        verify(inventoryService).receivePurchaseOrderStock(
                1L, 5L, 4, "GR:gr-key-1:200", 7L,
                "Supplier delivery received and checked", 70L
        );
    }

    @Test
    void goodsReceiptExactRetryDoesNotReceiveInventoryTwice() {
        AtomicReference<GoodsReceipt> savedReceipt = new AtomicReference<>();
        when(goodsReceiptRepository.findByIdempotencyKey("gr-retry-key"))
                .thenAnswer(invocation -> Optional.ofNullable(savedReceipt.get()));
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));
        when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class))).thenAnswer(invocation -> {
            GoodsReceipt receipt = invocation.getArgument(0);
            ReflectionTestUtils.setField(receipt, "id", 71L);
            ReflectionTestUtils.setField(receipt, "receiptNumber", "GR-TEST-071");
            savedReceipt.set(receipt);
            return receipt;
        });
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GoodsReceiptRequest request = new GoodsReceiptRequest(
                "Supplier delivery received and checked",
                "gr-retry-key",
                List.of(new GoodsReceiptItemRequest(200L, 4))
        );

        GoodsReceiptResponse first = service.receiveGoods(20L, request, "manager.one");
        GoodsReceiptResponse retry = service.receiveGoods(20L, request, "manager.one");

        assertEquals(first.receiptNumber(), retry.receiptNumber());
        assertEquals(4, orderItem.getReceivedQuantity());
        assertEquals("PARTIALLY_RECEIVED", order.getStatus());
        verify(goodsReceiptRepository, times(1)).saveAndFlush(any(GoodsReceipt.class));
        verify(inventoryService, times(1)).receivePurchaseOrderStock(
                1L, 5L, 4, "GR:gr-retry-key:200", 7L,
                "Supplier delivery received and checked", 71L
        );
    }

    @Test
    void goodsReceiptCannotExceedOutstandingPurchaseOrderQuantity() {
        orderItem.setReceivedQuantity(8);
        when(goodsReceiptRepository.findByIdempotencyKey("gr-key-2")).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));

        assertThrows(IllegalArgumentException.class, () -> service.receiveGoods(
                20L,
                new GoodsReceiptRequest(
                        "Over delivery attempt", "gr-key-2",
                        List.of(new GoodsReceiptItemRequest(200L, 3))
                ),
                "manager.one"
        ));
        verify(goodsReceiptRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void supplierReturnExactRetryDoesNotDeductInventoryTwice() {
        order.setStatus("RECEIVED");
        orderItem.setReceivedQuantity(5);
        AtomicReference<SupplierReturn> savedReturn = new AtomicReference<>();
        when(supplierReturnRepository.findByIdempotencyKey("supplier-return-retry"))
                .thenAnswer(invocation -> Optional.ofNullable(savedReturn.get()));
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));
        when(supplierReturnRepository.saveAndFlush(any(SupplierReturn.class))).thenAnswer(invocation -> {
            SupplierReturn record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 81L);
            ReflectionTestUtils.setField(record, "returnNumber", "SUP-RET-081");
            savedReturn.set(record);
            return record;
        });
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SupplierReturnRequest request = new SupplierReturnRequest(
                "Damaged shipment",
                "supplier-return-retry",
                List.of(new SupplierReturnItemRequest(200L, 2))
        );

        SupplierReturnResponse first = service.returnGoodsToSupplier(
                20L, request, "manager.one"
        );
        SupplierReturnResponse retry = service.returnGoodsToSupplier(
                20L, request, "manager.one"
        );

        assertEquals(first.returnNumber(), retry.returnNumber());
        assertEquals(2, orderItem.getReturnedQuantity());
        verify(supplierReturnRepository, times(1)).saveAndFlush(any(SupplierReturn.class));
        verify(inventoryService, times(1)).returnStockToSupplier(
                1L, 5L, 2, "SUP-RET:supplier-return-retry:200", 7L,
                "Damaged shipment", 81L
        );
    }

    @Test
    void supplierReturnIdempotencyKeyCannotBeReusedWithDifferentPayload() {
        order.setStatus("RECEIVED");
        orderItem.setReceivedQuantity(5);
        SupplierReturn existing = new SupplierReturn();
        existing.setPurchaseOrder(order);
        existing.setProcessedByUsername("manager.one");
        existing.setReason("Damaged shipment");
        existing.setIdempotencyKey("supplier-return-existing");
        existing.setRequestFingerprint("different-fingerprint");
        existing.getItems().add(new SupplierReturnItem());

        when(supplierReturnRepository.findByIdempotencyKey("supplier-return-existing"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.returnGoodsToSupplier(
                20L,
                new SupplierReturnRequest(
                        "Damaged shipment",
                        "supplier-return-existing",
                        List.of(new SupplierReturnItemRequest(200L, 1))
                ),
                "manager.one"
        ));

        verifyNoInteractions(inventoryService);
        verify(supplierReturnRepository, never()).saveAndFlush(any());
    }

    @Test
    void supplierReturnCannotExceedReceivedQuantityNotPreviouslyReturned() {
        order.setStatus("RECEIVED");
        orderItem.setReceivedQuantity(5);
        orderItem.setReturnedQuantity(2);
        when(supplierReturnRepository.findByIdempotencyKey("supplier-return-key"))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));

        assertThrows(IllegalArgumentException.class, () -> service.returnGoodsToSupplier(
                20L,
                new SupplierReturnRequest(
                        "Damaged shipment", "supplier-return-key",
                        List.of(new SupplierReturnItemRequest(200L, 4))
                ),
                "manager.one"
        ));
        verify(supplierReturnRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void supplierListingUsesBoundedPagesAndPreservesSupplierDtos() {
        when(supplierRepository.findAllByOrderByNameAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(supplier), PageRequest.of(0, 50), 1));

        var result = service.getSuppliers(0, 50);

        assertEquals(1, result.getTotalElements());
        assertEquals("Optical Frames Supplier", result.getContent().get(0).name());
        verify(supplierRepository).findAllByOrderByNameAsc(any(Pageable.class));
        assertThrows(IllegalArgumentException.class, () -> service.getSuppliers(0, 101));
    }

    @Test
    void purchaseOrderListingPagesIdsAndReturnsCompleteOrderDtos() {
        when(purchaseOrderRepository.findHistoryIds(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(20L), PageRequest.of(0, 25), 1));
        when(purchaseOrderRepository.findAllByIdIn(List.of(20L))).thenReturn(List.of(order));

        var result = service.getPurchaseOrders(0, 25);

        assertEquals(1, result.getTotalElements());
        assertEquals("PO-TEST-002", result.getContent().get(0).orderNumber());
        assertEquals(1, result.getContent().get(0).items().size());
        verify(purchaseOrderRepository).findHistoryIds(any(Pageable.class));
        verify(purchaseOrderRepository).findAllByIdIn(List.of(20L));
        assertThrows(IllegalArgumentException.class, () -> service.getPurchaseOrders(0, 101));
    }

    @Test
    void procurementAuditHistoryReturnsEmptyListsForExistingOrder() {
        when(purchaseOrderRepository.existsById(20L)).thenReturn(true);
        when(goodsReceiptRepository.findHistoryIds(eq(20L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
        when(supplierReturnRepository.findHistoryIds(eq(20L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        assertTrue(service.getGoodsReceipts(20L, 0, 25).isEmpty());
        assertTrue(service.getSupplierReturns(20L, 0, 25).isEmpty());
        verify(goodsReceiptRepository).findHistoryIds(eq(20L), any(Pageable.class));
        verify(supplierReturnRepository).findHistoryIds(eq(20L), any(Pageable.class));
    }

    @Test
    void procurementAuditHistoryRejectsMissingOrInvalidOrder() {
        when(purchaseOrderRepository.existsById(999L)).thenReturn(false);
        assertThrows(nmk_backend.exception.InventoryNotFoundException.class, () -> service.getGoodsReceipts(999L, 0, 25));
        assertThrows(nmk_backend.exception.InventoryNotFoundException.class, () -> service.getSupplierReturns(999L, 0, 25));
        assertThrows(IllegalArgumentException.class, () -> service.getGoodsReceipts(0L, 0, 25));
        assertThrows(IllegalArgumentException.class, () -> service.getSupplierReturns(null, 0, 25));
        verifyNoInteractions(goodsReceiptRepository, supplierReturnRepository);
    }

    @Test
    void procurementCostReportSeparatesGoodsReceivedFromSupplierReturns() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 21);
        java.time.LocalDateTime receivedAt = day.atTime(10, 0);
        java.time.LocalDateTime returnedAt = day.atTime(15, 0);

        GoodsReceipt receipt = new GoodsReceipt();
        ReflectionTestUtils.setField(receipt, "id", 70L);
        ReflectionTestUtils.setField(receipt, "receiptNumber", "GR-070");
        ReflectionTestUtils.setField(receipt, "createdAt", receivedAt);
        receipt.setPurchaseOrder(order);
        receipt.setReceivedByUsername("manager.one");
        GoodsReceiptItem received = new GoodsReceiptItem();
        received.setGoodsReceipt(receipt);
        received.setPurchaseOrderItem(orderItem);
        received.setQuantity(4);
        received.setUnitCost(new BigDecimal("250.00"));
        receipt.getItems().add(received);

        SupplierReturn supplierReturn = new SupplierReturn();
        ReflectionTestUtils.setField(supplierReturn, "id", 80L);
        ReflectionTestUtils.setField(supplierReturn, "returnNumber", "SUP-RET-080");
        ReflectionTestUtils.setField(supplierReturn, "createdAt", returnedAt);
        supplierReturn.setPurchaseOrder(order);
        supplierReturn.setProcessedByUsername("manager.one");
        SupplierReturnItem returned = new SupplierReturnItem();
        returned.setSupplierReturn(supplierReturn);
        returned.setPurchaseOrderItem(orderItem);
        returned.setQuantity(1);
        returned.setUnitCost(new BigDecimal("250.00"));
        supplierReturn.getItems().add(returned);

        when(locationRepository.findById(1L)).thenReturn(Optional.of(location));
        when(goodsReceiptRepository.findReportIds(eq(1L), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(70L), invocation.getArgument(3), 1));
        when(goodsReceiptRepository.findAllByIdIn(List.of(70L))).thenReturn(List.of(receipt));
        when(supplierReturnRepository.findReportIds(eq(1L), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(80L), invocation.getArgument(3), 1));
        when(supplierReturnRepository.findAllByIdIn(List.of(80L))).thenReturn(List.of(supplierReturn));
        when(locationRepository.existsById(1L)).thenReturn(true);
        when(goodsReceiptRepository.findCostReportEntries(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        reportRow("RECEIVED", "GR-070", receivedAt),
                        reportRow("SUPPLIER_RETURN", "SUP-RET-080", returnedAt)
                ), PageRequest.of(0, 50), 2));

        ProcurementCostReportResponse report = service.getProcurementCostReport(1L, day, day);

        assertEquals(4L, report.receivedQuantity());
        assertEquals(1L, report.returnedQuantity());
        assertEquals(new BigDecimal("1000.00"), report.receivedCost());
        assertEquals(new BigDecimal("250.00"), report.returnedCost());
        assertEquals(new BigDecimal("750.00"), report.netRecordedProcurementCost());
        assertEquals(List.of("RECEIVED", "SUPPLIER_RETURN"),
                report.entries().stream().map(ProcurementCostReportResponse.Entry::type).toList());
    }


    @Test
    void procurementCostReportReadsAllReceiptsAcrossBoundedPages() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 21);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(location));
        List<Long> allIds = new java.util.ArrayList<>();
        List<GoodsReceipt> allReceipts = new java.util.ArrayList<>();
        for (long id = 1; id <= 201; id++) {
            GoodsReceipt receipt = new GoodsReceipt();
            ReflectionTestUtils.setField(receipt, "id", id);
            ReflectionTestUtils.setField(receipt, "receiptNumber", "GR-" + id);
            ReflectionTestUtils.setField(receipt, "createdAt", day.atStartOfDay().plusMinutes(id));
            receipt.setPurchaseOrder(order);
            receipt.setReceivedByUsername("manager.one");
            GoodsReceiptItem item = new GoodsReceiptItem();
            item.setGoodsReceipt(receipt);
            item.setPurchaseOrderItem(orderItem);
            item.setQuantity(1);
            item.setUnitCost(new BigDecimal("1.00"));
            receipt.getItems().add(item);
            allIds.add(id);
            allReceipts.add(receipt);
        }

        when(goodsReceiptRepository.findReportIds(eq(1L), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    int start = (int) pageable.getOffset();
                    int end = Math.min(start + pageable.getPageSize(), allIds.size());
                    return new PageImpl<>(allIds.subList(start, end), pageable, allIds.size());
                });
        when(goodsReceiptRepository.findAllByIdIn(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    return allReceipts.stream()
                            .filter(receipt -> ids.contains(ReflectionTestUtils.getField(receipt, "id")))
                            .toList();
                });
        when(supplierReturnRepository.findReportIds(eq(1L), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(3), 0));
        when(locationRepository.existsById(1L)).thenReturn(true);
        when(goodsReceiptRepository.findCostReportEntries(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 201));

        var report = service.getProcurementCostReport(1L, day, day);

        assertEquals(201L, report.receivedQuantity());
        assertEquals(new BigDecimal("201.00"), report.receivedCost());
        assertEquals(0, report.entries().size());
        assertEquals(201L, report.totalEntries());
        assertTrue(report.entriesHasNext());
        verify(goodsReceiptRepository, times(2))
                .findReportIds(eq(1L), any(), any(), any(Pageable.class));
        verify(goodsReceiptRepository, times(2)).findAllByIdIn(anyList());
    }

    @Test
    void procurementCostReportEntriesArePagedAndValidateBounds() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 21);
        when(locationRepository.existsById(1L)).thenReturn(true);
        when(goodsReceiptRepository.findCostReportEntries(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(reportRow("RECEIVED", "GR-071", day.atTime(10, 0))),
                        PageRequest.of(1, 1), 3
                ));

        var page = service.getProcurementCostReportEntries(1L, day, day, 1, 1);

        assertEquals(3L, page.getTotalElements());
        assertEquals(1, page.getNumber());
        assertEquals(1, page.getSize());
        assertTrue(page.hasNext());
        assertEquals("GR-071", page.getContent().get(0).reference());
        verify(goodsReceiptRepository).findCostReportEntries(eq(1L), any(), any(), any(Pageable.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.getProcurementCostReportEntries(1L, day, day, -1, 25));
        assertThrows(IllegalArgumentException.class,
                () -> service.getProcurementCostReportEntries(1L, day, day, 0, 101));
    }

    @Test
    void procurementCostReportRejectsInvalidDateRangeAndMissingLocation() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 21);
        assertThrows(IllegalArgumentException.class,
                () -> service.getProcurementCostReport(1L, day, day.minusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.getProcurementCostReport(1L, day, day.plusDays(367)));
        assertThrows(IllegalArgumentException.class,
                () -> service.getProcurementCostReport(null, day, day));
        verifyNoInteractions(goodsReceiptRepository, supplierReturnRepository);
    }

    private ProcurementCostReportRow reportRow(
            String type, String reference, java.time.LocalDateTime createdAt
    ) {
        return new ProcurementCostReportRow() {
            public String getType() { return type; }
            public String getReference() { return reference; }
            public Long getPurchaseOrderId() { return 20L; }
            public String getPurchaseOrderNumber() { return "PO-TEST-002"; }
            public String getSupplierName() { return "Optical Frames Supplier"; }
            public String getSku() { return "FRAME-005"; }
            public Integer getQuantity() { return 1; }
            public BigDecimal getUnitCost() { return new BigDecimal("250.00"); }
            public BigDecimal getTotalCost() { return new BigDecimal("250.00"); }
            public String getRecordedBy() { return "manager.one"; }
            public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        };
    }

}
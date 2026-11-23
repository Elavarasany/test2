package nmk_backend.service;

import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class ProcurementMySqlIntegrationTest {
    @Autowired private ProcurementService procurement;
    @Autowired private AdminRepository admins;
    @Autowired private InventoryLocationRepository locations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryBalanceRepository balances;
    @Autowired private SupplierRepository suppliers;

    @Test
    void supplierNameUniqueConstraintIsCaseInsensitiveOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Supplier first = new Supplier();
        first.setName("Optical Supplier " + suffix);
        suppliers.saveAndFlush(first);

        Supplier caseVariant = new Supplier();
        caseVariant.setName("OPTICAL SUPPLIER " + suffix);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> suppliers.saveAndFlush(caseVariant));
    }

    @Test
    void purchaseOrderReceiptSupplierReturnAndRetriesStayConsistentOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "mysql-procurement-" + suffix;

        Admin manager = new Admin();
        manager.setUsername(username);
        manager.setPassword("test-only-password");
        admins.saveAndFlush(manager);

        InventoryLocation location = new InventoryLocation();
        location.setCode("PR-" + suffix);
        location.setName("MySQL Procurement " + suffix);
        location.setActive(true);
        location = locations.saveAndFlush(location);

        Product product = new Product();
        product.setName("MySQL Procurement Frame " + suffix);
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = products.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("PR-" + suffix);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variants.saveAndFlush(variant);

        SupplierResponse supplier = procurement.createSupplier(
                new SupplierCreateRequest("Supplier " + suffix, "Contact", "9876543210", null, "Test address")
        );
        PurchaseOrderCreateRequest create = new PurchaseOrderCreateRequest(
                supplier.id(), location.getId(), "MySQL procurement lifecycle",
                List.of(new PurchaseOrderItemRequest(variant.getId(), 5, new BigDecimal("35.00"))),
                "MYSQL-PO-" + suffix
        );
        PurchaseOrderResponse draft = procurement.createPurchaseOrder(create, username);
        assertEquals("DRAFT", draft.status());
        assertEquals(new BigDecimal("175.00"), draft.subtotal());
        assertTrue(balances.findByLocationIdAndVariantId(location.getId(), variant.getId()).isEmpty(),
                "Creating a purchase order must not increase stock");

        PurchaseOrderResponse ordered = procurement.submitPurchaseOrder(draft.id());
        PurchaseOrderResponse submitReplay = procurement.submitPurchaseOrder(draft.id());
        assertEquals("ORDERED", ordered.status());
        assertEquals(ordered.id(), submitReplay.id());

        Long lineId = ordered.items().get(0).id();
        GoodsReceiptRequest firstReceiptRequest = new GoodsReceiptRequest(
                "First partial delivery checked", "MYSQL-GR-1-" + suffix,
                List.of(new GoodsReceiptItemRequest(lineId, 4))
        );
        GoodsReceiptResponse firstReceipt = procurement.receiveGoods(draft.id(), firstReceiptRequest, username);
        GoodsReceiptResponse firstReceiptReplay = procurement.receiveGoods(draft.id(), firstReceiptRequest, username);
        assertEquals(firstReceipt.id(), firstReceiptReplay.id());
        String otherUsername = "mysql-procurement-other-" + suffix;
        Admin otherManager = new Admin();
        otherManager.setUsername(otherUsername);
        otherManager.setPassword("test-only-password");
        admins.saveAndFlush(otherManager);
        assertThrows(IllegalArgumentException.class,
                () -> procurement.receiveGoods(draft.id(), firstReceiptRequest, otherUsername),
                "A different staff member must not replay another user's goods receipt key");
        assertEquals("PARTIALLY_RECEIVED", procurement.getPurchaseOrder(draft.id()).status());
        assertEquals(4, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity(),
                "A retried receipt must not add the same stock twice");

        GoodsReceiptResponse finalReceipt = procurement.receiveGoods(
                draft.id(),
                new GoodsReceiptRequest("Final unit received", "MYSQL-GR-2-" + suffix,
                        List.of(new GoodsReceiptItemRequest(lineId, 1))),
                username
        );
        assertEquals("RECEIVED", procurement.getPurchaseOrder(draft.id()).status());
        assertEquals(5, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity());

        SupplierReturnRequest returnRequest = new SupplierReturnRequest(
                "Two units returned to supplier", "MYSQL-SR-" + suffix,
                List.of(new SupplierReturnItemRequest(lineId, 2))
        );
        SupplierReturnResponse supplierReturn = procurement.returnGoodsToSupplier(draft.id(), returnRequest, username);
        SupplierReturnResponse supplierReturnReplay = procurement.returnGoodsToSupplier(draft.id(), returnRequest, username);
        assertEquals(supplierReturn.id(), supplierReturnReplay.id());
        assertThrows(IllegalArgumentException.class,
                () -> procurement.returnGoodsToSupplier(draft.id(), returnRequest, otherUsername),
                "A different staff member must not replay another user's supplier return key");
        assertEquals(3, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity(),
                "A retried supplier return must not deduct stock twice");

        ProcurementCostReportResponse report = procurement.getProcurementCostReport(
                location.getId(), LocalDate.now(), LocalDate.now()
        );
        assertEquals(5L, report.receivedQuantity());
        assertEquals(2L, report.returnedQuantity());
        assertEquals(new BigDecimal("175.00"), report.receivedCost());
        assertEquals(new BigDecimal("70.00"), report.returnedCost());
        assertEquals(new BigDecimal("105.00"), report.netRecordedProcurementCost());
        assertEquals(3L, report.totalEntries());
        assertEquals(3, report.entries().size());
        assertEquals(List.of("RECEIVED", "RECEIVED", "SUPPLIER_RETURN"),
                report.entries().stream().map(ProcurementCostReportResponse.Entry::type).toList());

        var firstPage = procurement.getProcurementCostReportEntries(
                location.getId(), LocalDate.now(), LocalDate.now(), 0, 1
        );
        var secondPage = procurement.getProcurementCostReportEntries(
                location.getId(), LocalDate.now(), LocalDate.now(), 1, 1
        );
        assertEquals(3L, firstPage.getTotalElements());
        assertEquals(1, firstPage.getContent().size());
        assertTrue(firstPage.hasNext());
        assertEquals("RECEIVED", firstPage.getContent().get(0).type());
        assertEquals(1, secondPage.getContent().size());
        assertTrue(secondPage.hasNext());
        assertEquals("RECEIVED", secondPage.getContent().get(0).type());
        var thirdPage = procurement.getProcurementCostReportEntries(
                location.getId(), LocalDate.now(), LocalDate.now(), 2, 1
        );
        assertEquals(1, thirdPage.getContent().size());
        assertFalse(thirdPage.hasNext());
        assertEquals("SUPPLIER_RETURN", thirdPage.getContent().get(0).type());
    }

    @Test
    void failedMultiLineGoodsReceiptRollsBackEarlierInventoryAndPurchaseOrderChanges() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "mysql-procurement-rollback-" + suffix;

        Admin manager = new Admin();
        manager.setUsername(username);
        manager.setPassword("test-only-password");
        admins.saveAndFlush(manager);

        InventoryLocation location = new InventoryLocation();
        location.setCode("PR-RB-" + suffix);
        location.setName("MySQL Procurement Rollback " + suffix);
        location.setActive(true);
        location = locations.saveAndFlush(location);

        Product firstProduct = new Product();
        firstProduct.setName("Rollback First " + suffix);
        firstProduct.setPrice(new BigDecimal("100.00"));
        firstProduct.setStockQuantity(0);
        firstProduct.setActive(true);
        firstProduct = products.saveAndFlush(firstProduct);

        ProductVariant firstVariant = new ProductVariant();
        firstVariant.setProduct(firstProduct);
        firstVariant.setSku("PR-RB-A-" + suffix);
        firstVariant.setVariantName("Black");
        firstVariant.setAttributes(Map.of("color", "Black"));
        firstVariant.setActive(true);
        firstVariant = variants.saveAndFlush(firstVariant);

        Product secondProduct = new Product();
        secondProduct.setName("Rollback Second " + suffix);
        secondProduct.setPrice(new BigDecimal("100.00"));
        secondProduct.setStockQuantity(0);
        secondProduct.setActive(true);
        secondProduct = products.saveAndFlush(secondProduct);

        ProductVariant secondVariant = new ProductVariant();
        secondVariant.setProduct(secondProduct);
        secondVariant.setSku("PR-RB-B-" + suffix);
        secondVariant.setVariantName("Brown");
        secondVariant.setAttributes(Map.of("color", "Brown"));
        secondVariant.setActive(true);
        secondVariant = variants.saveAndFlush(secondVariant);

        SupplierResponse supplier = procurement.createSupplier(
                new SupplierCreateRequest("Rollback Supplier " + suffix, "Contact", "9876543210", null, "Test address")
        );
        PurchaseOrderResponse draft = procurement.createPurchaseOrder(
                new PurchaseOrderCreateRequest(
                        supplier.id(), location.getId(), "Verify receipt transaction rollback",
                        List.of(
                                new PurchaseOrderItemRequest(firstVariant.getId(), 2, new BigDecimal("35.00")),
                                new PurchaseOrderItemRequest(secondVariant.getId(), 2, new BigDecimal("40.00"))
                        ),
                        "MYSQL-PO-RB-" + suffix
                ),
                username
        );
        procurement.submitPurchaseOrder(draft.id());

        secondProduct.setActive(false);
        products.saveAndFlush(secondProduct);

        assertThrows(nmk_backend.exception.InvalidInventoryOperationException.class, () -> procurement.receiveGoods(
                draft.id(),
                new GoodsReceiptRequest(
                        "Second line is intentionally no longer receivable",
                        "MYSQL-GR-RB-" + suffix,
                        List.of(
                                new GoodsReceiptItemRequest(draft.items().get(0).id(), 1),
                                new GoodsReceiptItemRequest(draft.items().get(1).id(), 1)
                        )
                ),
                username
        ));

        assertEquals("ORDERED", procurement.getPurchaseOrder(draft.id()).status());
        assertTrue(procurement.getGoodsReceipts(draft.id(), 0, 25).isEmpty(),
                "The failed receipt document must roll back");
        assertTrue(balances.findByLocationIdAndVariantId(location.getId(), firstVariant.getId()).isEmpty(),
                "The first line's stock increase must roll back when a later line fails");
        assertTrue(balances.findByLocationIdAndVariantId(location.getId(), secondVariant.getId()).isEmpty());
    }
}

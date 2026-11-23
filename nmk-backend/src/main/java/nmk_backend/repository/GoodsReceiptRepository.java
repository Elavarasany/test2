package nmk_backend.repository;

import nmk_backend.entity.GoodsReceipt;
import nmk_backend.dto.ProcurementCostReportRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {
    Optional<GoodsReceipt> findByIdempotencyKey(String idempotencyKey);


    @Query(value = """
            SELECT report_rows.type AS type,
                   report_rows.reference AS reference,
                   report_rows.purchase_order_id AS purchaseOrderId,
                   report_rows.purchase_order_number AS purchaseOrderNumber,
                   report_rows.supplier_name AS supplierName,
                   report_rows.sku AS sku,
                   report_rows.quantity AS quantity,
                   report_rows.unit_cost AS unitCost,
                   report_rows.total_cost AS totalCost,
                   report_rows.recorded_by AS recordedBy,
                   report_rows.created_at AS createdAt
            FROM (
                SELECT 'RECEIVED' AS type, gr.receipt_number AS reference,
                       po.id AS purchase_order_id, po.order_number AS purchase_order_number,
                       s.name AS supplier_name, pv.sku AS sku, gri.quantity AS quantity,
                       gri.unit_cost AS unit_cost,
                       (gri.quantity * gri.unit_cost) AS total_cost,
                       gr.received_by_username AS recorded_by, gr.created_at AS created_at,
                       gri.id AS item_id
                FROM goods_receipt_items gri
                JOIN goods_receipts gr ON gr.id = gri.goods_receipt_id
                JOIN purchase_orders po ON po.id = gr.purchase_order_id
                JOIN suppliers s ON s.id = po.supplier_id
                JOIN purchase_order_items poi ON poi.id = gri.purchase_order_item_id
                JOIN product_variants pv ON pv.id = poi.variant_id
                WHERE po.location_id = :locationId
                  AND gr.created_at >= :fromDate AND gr.created_at < :toDate
                UNION ALL
                SELECT 'SUPPLIER_RETURN' AS type, sr.return_number AS reference,
                       po.id AS purchase_order_id, po.order_number AS purchase_order_number,
                       s.name AS supplier_name, pv.sku AS sku, sri.quantity AS quantity,
                       sri.unit_cost AS unit_cost,
                       (sri.quantity * sri.unit_cost) AS total_cost,
                       sr.processed_by_username AS recorded_by, sr.created_at AS created_at,
                       sri.id AS item_id
                FROM supplier_return_items sri
                JOIN supplier_returns sr ON sr.id = sri.supplier_return_id
                JOIN purchase_orders po ON po.id = sr.purchase_order_id
                JOIN suppliers s ON s.id = po.supplier_id
                JOIN purchase_order_items poi ON poi.id = sri.purchase_order_item_id
                JOIN product_variants pv ON pv.id = poi.variant_id
                WHERE po.location_id = :locationId
                  AND sr.created_at >= :fromDate AND sr.created_at < :toDate
            ) report_rows
            ORDER BY report_rows.created_at ASC, report_rows.type ASC,
                     report_rows.reference ASC, report_rows.item_id ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT gri.id AS item_id
                FROM goods_receipt_items gri
                JOIN goods_receipts gr ON gr.id = gri.goods_receipt_id
                JOIN purchase_orders po ON po.id = gr.purchase_order_id
                WHERE po.location_id = :locationId
                  AND gr.created_at >= :fromDate AND gr.created_at < :toDate
                UNION ALL
                SELECT sri.id AS item_id
                FROM supplier_return_items sri
                JOIN supplier_returns sr ON sr.id = sri.supplier_return_id
                JOIN purchase_orders po ON po.id = sr.purchase_order_id
                WHERE po.location_id = :locationId
                  AND sr.created_at >= :fromDate AND sr.created_at < :toDate
            ) report_count
            """,
            nativeQuery = true)
    Page<ProcurementCostReportRow> findCostReportEntries(
            @Param("locationId") Long locationId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<GoodsReceipt> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);

    @Query("""
            select receipt.id from GoodsReceipt receipt
            where receipt.purchaseOrder.id = :purchaseOrderId
            """)
    Page<Long> findHistoryIds(@Param("purchaseOrderId") Long purchaseOrderId, Pageable pageable);


    @Query("""
            select receipt.id
            from GoodsReceipt receipt
            where receipt.purchaseOrder.location.id = :locationId
              and receipt.createdAt >= :from
              and receipt.createdAt < :to
            """)
    Page<Long> findReportIds(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<GoodsReceipt> findAllByIdIn(List<Long> ids);
}

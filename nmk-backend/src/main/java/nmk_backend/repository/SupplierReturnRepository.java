package nmk_backend.repository;

import nmk_backend.entity.SupplierReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {
    Optional<SupplierReturn> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<SupplierReturn> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);

    @Query("""
            select supplierReturn.id from SupplierReturn supplierReturn
            where supplierReturn.purchaseOrder.id = :purchaseOrderId
            """)
    Page<Long> findHistoryIds(@Param("purchaseOrderId") Long purchaseOrderId, Pageable pageable);


    @Query("""
            select supplierReturn.id
            from SupplierReturn supplierReturn
            where supplierReturn.purchaseOrder.location.id = :locationId
              and supplierReturn.createdAt >= :from
              and supplierReturn.createdAt < :to
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
    List<SupplierReturn> findAllByIdIn(List<Long> ids);
}

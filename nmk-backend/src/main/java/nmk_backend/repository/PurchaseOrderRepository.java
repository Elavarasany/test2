package nmk_backend.repository;

import nmk_backend.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    @EntityGraph(attributePaths = {"items", "items.variant", "items.variant.product", "supplier", "location"})
    List<PurchaseOrder> findAllByOrderByCreatedAtDesc();

    @Query("""
            select purchaseOrder.id from PurchaseOrder purchaseOrder
            order by purchaseOrder.createdAt desc, purchaseOrder.id desc
            """)
    Page<Long> findHistoryIds(Pageable pageable);

    @EntityGraph(attributePaths = {"items", "items.variant", "items.variant.product", "supplier", "location"})
    List<PurchaseOrder> findAllByIdIn(List<Long> ids);


    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") Long id);
}

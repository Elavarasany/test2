package nmk_backend.repository;

import nmk_backend.entity.PurchaseOrderItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PurchaseOrderItem i where i.id = :itemId and i.purchaseOrder.id = :orderId")
    Optional<PurchaseOrderItem> findByOrderAndIdForUpdate(
            @Param("orderId") Long orderId,
            @Param("itemId") Long itemId
    );
}
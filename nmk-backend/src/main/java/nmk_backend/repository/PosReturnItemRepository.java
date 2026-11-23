package nmk_backend.repository;

import nmk_backend.entity.PosReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface PosReturnItemRepository extends JpaRepository<PosReturnItem, Long> {

    @Query("select coalesce(sum(i.quantity), 0) from PosReturnItem i where i.saleItem.id = :saleItemId and i.returnRecord.status = 'COMPLETED'")
    Long sumReturnedQuantityBySaleItemId(@Param("saleItemId") Long saleItemId);

    @Query("select coalesce(sum(i.refundAmount), 0) from PosReturnItem i where i.saleItem.id = :saleItemId and i.returnRecord.status = 'COMPLETED'")
    BigDecimal sumRefundedAmountBySaleItemId(@Param("saleItemId") Long saleItemId);
}
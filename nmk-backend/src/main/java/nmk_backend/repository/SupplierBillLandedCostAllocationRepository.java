package nmk_backend.repository;

import nmk_backend.entity.SupplierBillLandedCostAllocation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SupplierBillLandedCostAllocationRepository extends JpaRepository<SupplierBillLandedCostAllocation, Long> {
    @EntityGraph(attributePaths = {"goodsReceiptItem", "goodsReceiptItem.purchaseOrderItem",
            "goodsReceiptItem.purchaseOrderItem.variant"})
    List<SupplierBillLandedCostAllocation> findByBillIdOrderByIdAsc(Long billId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from SupplierBillLandedCostAllocation a where a.bill.id = :billId")
    BigDecimal sumAllocatedAmountByBillId(@Param("billId") Long billId);
    @Query("select coalesce(sum(a.allocatedAmount), 0) from SupplierBillLandedCostAllocation a where a.goodsReceiptItem.id = :receiptItemId")
    BigDecimal sumAllocatedAmountByGoodsReceiptItemId(@Param("receiptItemId") Long receiptItemId);
}

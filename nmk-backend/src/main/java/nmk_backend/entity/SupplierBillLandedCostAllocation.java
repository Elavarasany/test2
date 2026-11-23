package nmk_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_bill_landed_cost_allocations",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_bill_receipt_item",
                columnNames = {"supplier_bill_id", "goods_receipt_item_id"}))
public class SupplierBillLandedCostAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_bill_id", nullable = false)
    private SupplierBill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_item_id", nullable = false)
    private GoodsReceiptItem goodsReceiptItem;

    @Column(name = "base_goods_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseGoodsValue;

    @Column(name = "allocated_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public SupplierBill getBill() { return bill; }
    public void setBill(SupplierBill value) { bill = value; }
    public GoodsReceiptItem getGoodsReceiptItem() { return goodsReceiptItem; }
    public void setGoodsReceiptItem(GoodsReceiptItem value) { goodsReceiptItem = value; }
    public BigDecimal getBaseGoodsValue() { return baseGoodsValue; }
    public void setBaseGoodsValue(BigDecimal value) { baseGoodsValue = value; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal value) { allocatedAmount = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

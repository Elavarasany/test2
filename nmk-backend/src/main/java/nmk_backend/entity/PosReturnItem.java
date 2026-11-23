package nmk_backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "pos_return_items",
        indexes = {
                @Index(name = "idx_pos_return_items_return", columnList = "return_id"),
                @Index(name = "idx_pos_return_items_sale_item", columnList = "sale_item_id")
        }
)
public class PosReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private PosReturn returnRecord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_item_id", nullable = false)
    private PosSaleItem saleItem;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitRefundAmount;

    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "restock_disposition", nullable = false, length = 20)
    private String restockDisposition;

    @Column(name = "disposition_reason", length = 500)
    private String dispositionReason;

    public Long getId() { return id; }
    public PosReturn getReturnRecord() { return returnRecord; }
    public void setReturnRecord(PosReturn value) { this.returnRecord = value; }
    public PosSaleItem getSaleItem() { return saleItem; }
    public void setSaleItem(PosSaleItem value) { this.saleItem = value; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer value) { this.quantity = value; }
    public BigDecimal getUnitRefundAmount() { return unitRefundAmount; }
    public void setUnitRefundAmount(BigDecimal value) { this.unitRefundAmount = value; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal value) { this.refundAmount = value; }
    public String getRestockDisposition() { return restockDisposition; }
    public void setRestockDisposition(String value) { this.restockDisposition = value; }
    public String getDispositionReason() { return dispositionReason; }
    public void setDispositionReason(String value) { this.dispositionReason = value; }
}
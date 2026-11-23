package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Projection for the globally ordered, paginated receipt/return cost-report rows. */
public interface ProcurementCostReportRow {
    String getType();
    String getReference();
    Long getPurchaseOrderId();
    String getPurchaseOrderNumber();
    String getSupplierName();
    String getSku();
    Integer getQuantity();
    BigDecimal getUnitCost();
    BigDecimal getTotalCost();
    String getRecordedBy();
    LocalDateTime getCreatedAt();
}

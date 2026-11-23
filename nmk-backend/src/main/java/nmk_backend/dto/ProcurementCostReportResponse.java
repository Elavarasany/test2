package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProcurementCostReportResponse(
        Long locationId,
        String locationName,
        LocalDate from,
        LocalDate to,
        long receivedQuantity,
        long returnedQuantity,
        BigDecimal receivedCost,
        BigDecimal returnedCost,
        BigDecimal netRecordedProcurementCost,
        List<Entry> entries,
        long totalEntries,
        int entriesPage,
        int entriesSize,
        boolean entriesHasNext
) {
    public record Entry(
            String type,
            String reference,
            Long purchaseOrderId,
            String purchaseOrderNumber,
            String supplierName,
            String sku,
            int quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            String recordedBy,
            LocalDateTime createdAt
    ) {}
}

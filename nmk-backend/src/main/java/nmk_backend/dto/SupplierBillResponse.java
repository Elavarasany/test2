package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SupplierBillResponse(
        Long id,
        Long supplierId,
        String supplierName,
        Long purchaseOrderId,
        String purchaseOrderNumber,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal paidAmount,
        BigDecimal creditedAmount,
        BigDecimal outstandingAmount,
        String billType,
        String status,
        int landedCostAllocationCount,
        BigDecimal landedCostAllocatedAmount,
        LocalDateTime createdAt
) {}

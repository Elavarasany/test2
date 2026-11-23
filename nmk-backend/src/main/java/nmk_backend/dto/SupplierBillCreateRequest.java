package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierBillCreateRequest(
        Long supplierId,
        Long purchaseOrderId,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal amount,
        String billType,
        String idempotencyKey
) {}

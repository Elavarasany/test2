package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SupplierCreditNoteResponse(
        Long id,
        Long billId,
        Long supplierId,
        Long supplierReturnId,
        String supplierReturnNumber,
        String creditNoteNumber,
        LocalDate issueDate,
        BigDecimal amount,
        String reason,
        String status,
        String createdByUsername,
        LocalDateTime createdAt
) {}

package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierCreditNoteRequest(
        String creditNoteNumber,
        LocalDate issueDate,
        BigDecimal amount,
        String reason,
        Long supplierReturnId,
        String idempotencyKey
) {}

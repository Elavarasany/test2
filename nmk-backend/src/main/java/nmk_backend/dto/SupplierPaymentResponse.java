package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SupplierPaymentResponse(
        Long id,
        Long billId,
        Long supplierId,
        String paymentMethod,
        BigDecimal amount,
        String reference,
        Long shiftId,
        String status,
        String createdByUsername,
        LocalDateTime paidAt
) {}

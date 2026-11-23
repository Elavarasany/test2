package nmk_backend.dto;

import java.math.BigDecimal;

public record SupplierPaymentRequest(
        BigDecimal amount,
        String paymentMethod,
        String reference,
        Long shiftId,
        String idempotencyKey
) {}

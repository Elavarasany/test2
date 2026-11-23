package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpaySettlementProviderSummary(
        String settlementId,
        long amountPaise,
        String status,
        long feesPaise,
        long taxPaise,
        String currency,
        String utr,
        LocalDateTime createdAt,
        String rawPayload
) {}

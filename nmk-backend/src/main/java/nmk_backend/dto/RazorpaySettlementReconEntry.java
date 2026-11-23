package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpaySettlementReconEntry(
        String entityId,
        String type,
        long debitPaise,
        long creditPaise,
        long amountPaise,
        String currency,
        long feePaise,
        long taxPaise,
        boolean onHold,
        boolean settled,
        LocalDateTime createdAt,
        LocalDateTime settledAt,
        String settlementId,
        String settlementUtr,
        String paymentId,
        String orderId,
        String method,
        String disputeId,
        String rawPayload
) {}

package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpaySettlementEntryResponse(
        Long id,
        String providerEntityId,
        String entryType,
        long debitPaise,
        long creditPaise,
        long amountPaise,
        long feePaise,
        long taxPaise,
        String currency,
        boolean onHold,
        boolean settled,
        LocalDateTime providerCreatedAt,
        LocalDateTime providerSettledAt,
        String paymentId,
        String orderId,
        String settlementUtr,
        String method,
        String disputeId,
        String matchStatus,
        String matchMessage,
        Long localOrderId,
        Long localRefundId,
        Long localDisputeId
) {}

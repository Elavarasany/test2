package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpaySettlementExceptionResponse(
        Long settlementId,
        String providerSettlementId,
        String settlementStatus,
        String settlementReconciliationStatus,
        String settlementUtr,
        long settlementAmountPaise,
        Long entryId,
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
        String matchStatus,
        String matchMessage,
        Long localOrderId,
        Long localRefundId,
        Long localDisputeId
) {}

package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpayDisputeResponse(
        Long id,
        String providerDisputeId,
        String providerPaymentId,
        Long localOrderId,
        long amountPaise,
        long amountDeductedPaise,
        String currency,
        String reasonCode,
        String reasonDescription,
        LocalDateTime respondBy,
        String status,
        String phase,
        LocalDateTime providerCreatedAt,
        LocalDateTime lastProviderEventAt,
        String lastProviderEventType,
        String matchStatus,
        String matchMessage,
        LocalDateTime lastSyncedAt
) {}

package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpaySettlementSummaryResponse(
        Long id,
        String providerSettlementId,
        String status,
        long amountPaise,
        long feesPaise,
        long taxPaise,
        String currency,
        String utr,
        LocalDateTime providerCreatedAt,
        LocalDateTime settledAt,
        LocalDateTime lastSyncedAt,
        String reconciliationStatus,
        int totalEntries,
        int matchedEntries,
        int unmatchedEntries,
        int manualReviewEntries,
        int pendingEntries,
        long netRowsPaise,
        boolean totalsBalanced
) {}

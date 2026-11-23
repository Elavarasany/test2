package nmk_backend.dto;

public record RazorpaySettlementSyncResponse(
        int settlementCount,
        int entryCount,
        int pagesFetched,
        String message
) {}

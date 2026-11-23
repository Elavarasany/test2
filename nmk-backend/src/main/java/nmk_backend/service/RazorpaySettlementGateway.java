package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;

import java.util.List;

public interface RazorpaySettlementGateway {
    List<RazorpaySettlementReconEntry> fetchReconPage(
            int year, int month, Integer day, int count, int skip);
    RazorpaySettlementProviderSummary fetchSettlement(String settlementId);
}

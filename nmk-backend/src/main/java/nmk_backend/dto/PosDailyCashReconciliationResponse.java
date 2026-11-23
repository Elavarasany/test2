package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PosDailyCashReconciliationResponse(
        Long locationId,
        LocalDate businessDate,
        int shiftCount,
        int openShiftCount,
        int closedShiftCount,
        int discrepancyCount,
        BigDecimal expectedCashTotal,
        BigDecimal countedCashTotal,
        BigDecimal varianceTotal,
        List<ShiftEntry> shifts
) {
    public record ShiftEntry(
            Long shiftId,
            String registerCode,
            String cashierUsername,
            LocalDateTime openedAt,
            LocalDateTime closedAt,
            String status,
            BigDecimal openingFloat,
            BigDecimal ledgerExpectedCash,
            BigDecimal recordedExpectedCash,
            BigDecimal countedCash,
            BigDecimal calculatedVariance,
            BigDecimal recordedVariance,
            boolean ledgerConsistent,
            String closingReason
    ) {}
}

package nmk_backend.dto;

import nmk_backend.entity.PosShift;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PosShiftResponse(
        Long id,
        Long registerId,
        String registerCode,
        Long locationId,
        String openedByUsername,
        String closedByUsername,
        BigDecimal openingFloat,
        BigDecimal expectedCash,
        BigDecimal closingCashCount,
        BigDecimal cashVariance,
        String closingReason,
        String status,
        LocalDateTime openedAt,
        LocalDateTime closedAt
) {
    public static PosShiftResponse from(PosShift shift) {
        return new PosShiftResponse(
                shift.getId(),
                shift.getRegister().getId(),
                shift.getRegister().getRegisterCode(),
                shift.getRegister().getLocation().getId(),
                shift.getOpenedByUsername(),
                shift.getClosedByUsername(),
                shift.getOpeningFloat(),
                shift.getExpectedCash(),
                shift.getClosingCashCount(),
                shift.getCashVariance(),
                shift.getClosingReason(),
                shift.getStatus(),
                shift.getOpenedAt(),
                shift.getClosedAt()
        );
    }
}
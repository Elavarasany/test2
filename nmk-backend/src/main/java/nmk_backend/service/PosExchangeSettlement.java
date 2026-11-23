package nmk_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure settlement calculation for an exchange. This class does not record
 * payments or mutate inventory; the transactional exchange service owns those
 * side effects after all validation has succeeded.
 */
public final class PosExchangeSettlement {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private PosExchangeSettlement() {
    }

    public static Result calculate(BigDecimal returnedValue, BigDecimal replacementValue) {
        BigDecimal returned = normalize(returnedValue, "Returned value");
        BigDecimal replacement = normalize(replacementValue, "Replacement value");

        BigDecimal creditApplied = returned.min(replacement);
        BigDecimal actualRefund = returned.subtract(creditApplied).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal amountCollected = replacement.subtract(creditApplied).setScale(2, RoundingMode.UNNECESSARY);

        return new Result(returned, replacement, creditApplied, actualRefund, amountCollected);
    }

    private static BigDecimal normalize(BigDecimal value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.compareTo(ZERO) < 0 || value.precision() > 12) {
            throw new IllegalArgumentException(label + " must be a non-negative DECIMAL(12,2) amount");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " can have at most two decimal places", exception);
        }
    }

    public record Result(
            BigDecimal returnedValue,
            BigDecimal replacementValue,
            BigDecimal creditApplied,
            BigDecimal actualRefund,
            BigDecimal amountCollected
    ) {
        public Result {
            if (returnedValue.compareTo(creditApplied.add(actualRefund)) != 0
                    || replacementValue.compareTo(creditApplied.add(amountCollected)) != 0) {
                throw new IllegalArgumentException("Exchange settlement does not balance");
            }
        }
    }
}

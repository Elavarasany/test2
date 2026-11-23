package nmk_backend.service;

import nmk_backend.entity.PosSale;
import nmk_backend.entity.PosSaleItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Shared, deterministic pricing rules for returns and exchanges. */
public final class PosReturnPricing {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private PosReturnPricing() {
    }

    public static BigDecimal discountShareForLine(PosSale sale, PosSaleItem target) {
        Objects.requireNonNull(sale, "Original sale is required");
        Objects.requireNonNull(target, "Original sale line is required");
        if (sale.getDiscountAmount() == null || sale.getDiscountAmount().compareTo(ZERO) <= 0
                || sale.getSubtotal() == null || sale.getSubtotal().compareTo(ZERO) <= 0) {
            return ZERO;
        }

        List<PosSaleItem> ordered = sale.getItems().stream()
                .sorted(Comparator.comparing(PosSaleItem::getId))
                .toList();
        BigDecimal allocated = ZERO;
        for (int index = 0; index < ordered.size(); index++) {
            PosSaleItem item = ordered.get(index);
            BigDecimal share;
            if (index == ordered.size() - 1) {
                share = sale.getDiscountAmount().subtract(allocated);
            } else {
                share = sale.getDiscountAmount().multiply(item.getLineTotal())
                        .divide(sale.getSubtotal(), 2, RoundingMode.HALF_UP);
                BigDecimal remaining = sale.getDiscountAmount().subtract(allocated);
                if (share.compareTo(remaining) > 0) share = remaining;
            }
            if (Objects.equals(item.getId(), target.getId())) {
                return share.max(ZERO).setScale(2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(share);
        }
        throw new IllegalArgumentException("Original sale line was not found for discount allocation");
    }

    public static BigDecimal netLineValue(PosSale sale, PosSaleItem item) {
        return item.getLineTotal().subtract(discountShareForLine(sale, item))
                .max(ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** Uses the same remainder-on-final-quantity rule as ordinary returns. */
    public static BigDecimal refundForQuantity(
            BigDecimal netLineValue, int soldQuantity, int remainingQuantity,
            int requestedQuantity, BigDecimal previouslyRefunded
    ) {
        if (netLineValue == null || previouslyRefunded == null
                || soldQuantity <= 0 || remainingQuantity <= 0
                || requestedQuantity <= 0 || requestedQuantity > remainingQuantity) {
            throw new IllegalArgumentException("Return quantity and prior refund values are invalid");
        }
        BigDecimal refund;
        if (requestedQuantity == remainingQuantity) {
            refund = netLineValue.subtract(previouslyRefunded).setScale(2, RoundingMode.HALF_UP);
        } else {
            refund = netLineValue.multiply(BigDecimal.valueOf(requestedQuantity))
                    .divide(BigDecimal.valueOf(soldQuantity), 2, RoundingMode.HALF_UP);
        }
        if (refund.compareTo(ZERO) < 0) {
            throw new IllegalArgumentException("Calculated refund cannot be negative");
        }
        return refund;
    }
}

package nmk_backend.service;

import nmk_backend.entity.PosSale;
import nmk_backend.entity.PosSaleItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PosReturnPricingTest {

    @Test
    void allocatesSaleDiscountAcrossLinesWithRemainderOnLastLine() {
        PosSale sale = sale("100.00", "10.00");
        PosSaleItem first = item(1L, "33.33");
        PosSaleItem second = item(2L, "33.33");
        PosSaleItem third = item(3L, "33.34");
        sale.getItems().addAll(List.of(first, second, third));

        assertEquals(new BigDecimal("3.33"), PosReturnPricing.discountShareForLine(sale, first));
        assertEquals(new BigDecimal("3.33"), PosReturnPricing.discountShareForLine(sale, second));
        assertEquals(new BigDecimal("3.34"), PosReturnPricing.discountShareForLine(sale, third));
    }

    @Test
    void returnsRemainingRoundingRemainderOnFinalPartialReturn() {
        BigDecimal netLine = new BigDecimal("100.00");
        BigDecimal first = PosReturnPricing.refundForQuantity(netLine, 3, 3, 1, BigDecimal.ZERO);
        BigDecimal second = PosReturnPricing.refundForQuantity(netLine, 3, 2, 1, first);
        BigDecimal third = PosReturnPricing.refundForQuantity(netLine, 3, 1, 1, first.add(second));

        assertEquals(new BigDecimal("33.33"), first);
        assertEquals(new BigDecimal("33.33"), second);
        assertEquals(new BigDecimal("33.34"), third);
        assertEquals(new BigDecimal("100.00"), first.add(second).add(third));
    }

    @Test
    void calculatesNetLineValueAfterDiscountAllocation() {
        PosSale sale = sale("200.00", "20.00");
        PosSaleItem first = item(1L, "150.00");
        PosSaleItem second = item(2L, "50.00");
        sale.getItems().addAll(List.of(first, second));

        assertEquals(new BigDecimal("135.00"), PosReturnPricing.netLineValue(sale, first));
        assertEquals(new BigDecimal("45.00"), PosReturnPricing.netLineValue(sale, second));
    }

    @Test
    void rejectsQuantityBeyondRemainingOriginalQuantity() {
        assertThrows(IllegalArgumentException.class, () ->
                PosReturnPricing.refundForQuantity(
                        new BigDecimal("100.00"), 2, 1, 2, BigDecimal.ZERO
                ));
    }

    private PosSale sale(String subtotal, String discount) {
        PosSale sale = new PosSale();
        sale.setSubtotal(new BigDecimal(subtotal));
        sale.setDiscountAmount(new BigDecimal(discount));
        return sale;
    }

    private PosSaleItem item(Long id, String total) {
        PosSaleItem item = new PosSaleItem();
        try {
            Field idField = PosSaleItem.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(item, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to assign test sale-line identity", exception);
        }
        item.setLineTotal(new BigDecimal(total));
        return item;
    }
}

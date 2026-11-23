package nmk_backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PosExchangeSettlementTest {

    @Test
    void equalValueExchangeUsesAllCreditAndMovesNoMoney() {
        var result = PosExchangeSettlement.calculate(new BigDecimal("1250.00"), new BigDecimal("1250.00"));

        assertEquals(new BigDecimal("1250.00"), result.creditApplied());
        assertEquals(new BigDecimal("0.00"), result.actualRefund());
        assertEquals(new BigDecimal("0.00"), result.amountCollected());
    }

    @Test
    void higherValueReplacementCollectsOnlyDifference() {
        var result = PosExchangeSettlement.calculate(new BigDecimal("900.00"), new BigDecimal("1249.50"));

        assertEquals(new BigDecimal("900.00"), result.creditApplied());
        assertEquals(new BigDecimal("0.00"), result.actualRefund());
        assertEquals(new BigDecimal("349.50"), result.amountCollected());
    }

    @Test
    void lowerValueReplacementRefundsOnlyUnusedCredit() {
        var result = PosExchangeSettlement.calculate(new BigDecimal("1249.50"), new BigDecimal("900.00"));

        assertEquals(new BigDecimal("900.00"), result.creditApplied());
        assertEquals(new BigDecimal("349.50"), result.actualRefund());
        assertEquals(new BigDecimal("0.00"), result.amountCollected());
    }

    @Test
    void zeroValueExchangeDoesNotCreatePhantomTender() {
        var result = PosExchangeSettlement.calculate(new BigDecimal("0.00"), new BigDecimal("0.00"));

        assertEquals(new BigDecimal("0.00"), result.creditApplied());
        assertEquals(new BigDecimal("0.00"), result.actualRefund());
        assertEquals(new BigDecimal("0.00"), result.amountCollected());
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> PosExchangeSettlement.calculate(new BigDecimal("-0.01"), BigDecimal.ZERO));
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        assertThrows(IllegalArgumentException.class,
                () -> PosExchangeSettlement.calculate(new BigDecimal("10.001"), BigDecimal.ONE));
    }

    @Test
    void rejectsAmountsThatExceedDatabasePrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> PosExchangeSettlement.calculate(new BigDecimal("10000000000.00"), BigDecimal.ONE));
    }
}

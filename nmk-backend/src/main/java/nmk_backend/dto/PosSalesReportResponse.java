package nmk_backend.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
public record PosSalesReportResponse(
 Long locationId, LocalDate from, LocalDate to, int transactionCount,
 BigDecimal subtotal, BigDecimal discounts, BigDecimal netSalesBeforeRefunds,
 int returnCount, BigDecimal refunds, BigDecimal salesReversals,
 BigDecimal recordedRefundPayments, BigDecimal exchangeCreditApplied,
 BigDecimal netSalesAfterRefunds, Map<String,BigDecimal> salesByPaymentMethod,
 Map<String,BigDecimal> refundsByPaymentMethod, List<CashierTotals> cashiers,
 List<DailyTotals> daily, List<ProductTotals> topProducts
) {
 public record CashierTotals(String cashierUsername,int transactionCount,BigDecimal subtotal,BigDecimal discounts,BigDecimal netSales) {}
 public record DailyTotals(LocalDate date,int transactionCount,BigDecimal netSales,int returnCount,BigDecimal refunds,BigDecimal netAfterRefunds) {}
 public record ProductTotals(Long productId,String productName,long quantitySold,BigDecimal grossLineTotal) {}
}
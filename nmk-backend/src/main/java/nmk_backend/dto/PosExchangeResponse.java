package nmk_backend.dto;
import nmk_backend.entity.PosExchange;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record PosExchangeResponse(Long id,Long originalSaleId,String originalInvoiceNumber,Long returnId,
 String returnNumber,Long replacementSaleId,String replacementInvoiceNumber,Long shiftId,
 String processedByUsername,String reason,BigDecimal returnedValue,BigDecimal replacementValue,
 BigDecimal creditApplied,BigDecimal actualRefund,BigDecimal amountCollected,LocalDateTime createdAt) {
 public static PosExchangeResponse from(PosExchange e) {
  return new PosExchangeResponse(e.getId(),e.getOriginalSale().getId(),e.getOriginalSale().getInvoiceNumber(),
   e.getReturnRecord().getId(),e.getReturnRecord().getReturnNumber(),e.getReplacementSale().getId(),
   e.getReplacementSale().getInvoiceNumber(),e.getShift().getId(),e.getProcessedByUsername(),e.getReason(),
   e.getReturnedValue(),e.getReplacementValue(),e.getCreditApplied(),e.getActualRefund(),e.getAmountCollected(),e.getCreatedAt());
 }
}
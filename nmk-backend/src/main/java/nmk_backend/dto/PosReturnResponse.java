package nmk_backend.dto;

import nmk_backend.entity.PosRefundPayment;
import nmk_backend.entity.PosReturn;
import nmk_backend.entity.PosReturnItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PosReturnResponse(
        Long id,
        String returnNumber,
        Long saleId,
        String invoiceNumber,
        Long shiftId,
        String processedByUsername,
        String reason,
        BigDecimal refundTotal,
        String returnType,
        String status,
        LocalDateTime createdAt,
        List<ItemResponse> items,
        List<RefundPaymentResponse> refundPayments
) {
    public static PosReturnResponse from(PosReturn record) {
        return new PosReturnResponse(
                record.getId(),
                record.getReturnNumber(),
                record.getSale().getId(),
                record.getSale().getInvoiceNumber(),
                record.getShift().getId(),
                record.getProcessedByUsername(),
                record.getReason(),
                record.getRefundTotal(),
                record.getReturnType(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getItems().stream().map(ItemResponse::from).toList(),
                record.getRefundPayments().stream().map(RefundPaymentResponse::from).toList()
        );
    }

    public record ItemResponse(
            Long saleItemId,
            Long variantId,
            String sku,
            String productName,
            String variantName,
            Integer quantity,
            BigDecimal unitRefundAmount,
            BigDecimal refundAmount,
            String restockDisposition,
            String dispositionReason
    ) {
        private static ItemResponse from(PosReturnItem item) {
            return new ItemResponse(
                    item.getSaleItem().getId(),
                    item.getSaleItem().getVariantId(),
                    item.getSaleItem().getSku(),
                    item.getSaleItem().getProductName(),
                    item.getSaleItem().getVariantName(),
                    item.getQuantity(),
                    item.getUnitRefundAmount(),
                    item.getRefundAmount(),
                    item.getRestockDisposition(),
                    item.getDispositionReason()
            );
        }
    }

    public record RefundPaymentResponse(
            String paymentMethod,
            BigDecimal amount,
            String transactionReference
    ) {
        private static RefundPaymentResponse from(PosRefundPayment payment) {
            return new RefundPaymentResponse(
                    payment.getPaymentMethod(),
                    payment.getAmount(),
                    payment.getTransactionReference()
            );
        }
    }
}
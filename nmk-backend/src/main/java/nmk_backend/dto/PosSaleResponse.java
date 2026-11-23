package nmk_backend.dto;

import nmk_backend.entity.PosPayment;
import nmk_backend.entity.PosSale;
import nmk_backend.entity.PosSaleItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PosSaleResponse(
        Long id,
        String invoiceNumber,
        String customerName,
        String customerPhone,
        Long locationId,
        Long shiftId,
        String cashierUsername,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal grandTotal,
        String status,
        LocalDateTime createdAt,
        List<ItemResponse> items,
        List<PaymentResponse> payments
) {
    public static PosSaleResponse from(PosSale sale) {
        List<ItemResponse> items = sale.getItems().stream()
                .map(ItemResponse::from)
                .toList();

        List<PaymentResponse> payments = sale.getPayments().stream()
                .map(PaymentResponse::from)
                .toList();

        return new PosSaleResponse(
                sale.getId(),
                sale.getInvoiceNumber(),
                sale.getCustomerName(),
                sale.getCustomerPhone(),
                sale.getLocationId(),
                sale.getShiftId(),
                sale.getCashierUsername(),
                sale.getSubtotal(),
                sale.getDiscountAmount(),
                sale.getGrandTotal(),
                sale.getStatus(),
                sale.getCreatedAt(),
                items,
                payments
        );
    }

    public record ItemResponse(
            Long id,
            Long productId,
            Long variantId,
            String sku,
            String productName,
            String variantName,
            String variantAttributesJson,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
        private static ItemResponse from(PosSaleItem item) {
            return new ItemResponse(
                    item.getId(),
                    item.getProductId(),
                    item.getVariantId(),
                    item.getSku(),
                    item.getProductName(),
                    item.getVariantName(),
                    item.getVariantAttributesJson(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getLineTotal()
            );
        }
    }

    public record PaymentResponse(
            String paymentMethod,
            BigDecimal amount,
            BigDecimal tenderedAmount,
            BigDecimal changeAmount,
            String transactionReference
    ) {
        private static PaymentResponse from(PosPayment payment) {
            return new PaymentResponse(
                    payment.getPaymentMethod(),
                    payment.getAmount(),
                    payment.getTenderedAmount(),
                    payment.getChangeAmount(),
                    payment.getTransactionReference()
            );
        }
    }
}

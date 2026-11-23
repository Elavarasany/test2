package nmk_backend.dto;

import nmk_backend.entity.GoodsReceipt;
import java.time.LocalDateTime;
import java.util.List;

public record GoodsReceiptResponse(
        Long id, String receiptNumber, Long purchaseOrderId, String orderNumber,
        String receivedByUsername, String reason, LocalDateTime createdAt,
        List<ItemResponse> items
) {
    public static GoodsReceiptResponse from(GoodsReceipt receipt) {
        return new GoodsReceiptResponse(
                receipt.getId(), receipt.getReceiptNumber(),
                receipt.getPurchaseOrder().getId(), receipt.getPurchaseOrder().getOrderNumber(),
                receipt.getReceivedByUsername(), receipt.getReason(), receipt.getCreatedAt(),
                receipt.getItems().stream().map(item -> new ItemResponse(
                        item.getPurchaseOrderItem().getId(),
                        item.getPurchaseOrderItem().getVariant().getSku(),
                        item.getQuantity(), item.getUnitCost()
                )).toList()
        );
    }
    public record ItemResponse(Long purchaseOrderItemId, String sku, Integer quantity, java.math.BigDecimal unitCost) {}
}
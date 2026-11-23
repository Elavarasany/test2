package nmk_backend.dto;

import nmk_backend.entity.SupplierReturn;
import java.time.LocalDateTime;
import java.util.List;

public record SupplierReturnResponse(
        Long id, String returnNumber, Long purchaseOrderId, String orderNumber,
        String processedByUsername, String reason, LocalDateTime createdAt,
        List<ItemResponse> items
) {
    public static SupplierReturnResponse from(SupplierReturn record) {
        return new SupplierReturnResponse(
                record.getId(), record.getReturnNumber(),
                record.getPurchaseOrder().getId(), record.getPurchaseOrder().getOrderNumber(),
                record.getProcessedByUsername(), record.getReason(), record.getCreatedAt(),
                record.getItems().stream().map(item -> new ItemResponse(
                        item.getPurchaseOrderItem().getId(),
                        item.getPurchaseOrderItem().getVariant().getSku(),
                        item.getQuantity(), item.getUnitCost()
                )).toList()
        );
    }
    public record ItemResponse(Long purchaseOrderItemId, String sku, Integer quantity, java.math.BigDecimal unitCost) {}
}
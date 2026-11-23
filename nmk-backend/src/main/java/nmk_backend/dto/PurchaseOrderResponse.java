package nmk_backend.dto;

import nmk_backend.entity.PurchaseOrder;
import nmk_backend.entity.PurchaseOrderItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderResponse(
        Long id, String orderNumber, Long supplierId, String supplierName,
        Long locationId, String locationName, String status, String createdByUsername,
        String notes, BigDecimal subtotal, LocalDateTime createdAt, LocalDateTime orderedAt,
        List<ItemResponse> items
) {
    public static PurchaseOrderResponse from(PurchaseOrder order) {
        return new PurchaseOrderResponse(
                order.getId(), order.getOrderNumber(),
                order.getSupplier().getId(), order.getSupplier().getName(),
                order.getLocation().getId(), order.getLocation().getName(),
                order.getStatus(), order.getCreatedByUsername(), order.getNotes(),
                order.getSubtotal(), order.getCreatedAt(), order.getOrderedAt(),
                order.getItems().stream().map(ItemResponse::from).toList()
        );
    }

    public record ItemResponse(
            Long id, Long variantId, String sku, String productName, String variantName,
            Integer orderedQuantity, Integer receivedQuantity, Integer returnedQuantity,
            Integer outstandingQuantity, BigDecimal unitCost, BigDecimal lineTotal
    ) {
        private static ItemResponse from(PurchaseOrderItem item) {
            int outstanding = item.getOrderedQuantity() - item.getReceivedQuantity();
            return new ItemResponse(
                    item.getId(), item.getVariant().getId(), item.getVariant().getSku(),
                    item.getVariant().getProduct().getName(), item.getVariant().getVariantName(),
                    item.getOrderedQuantity(), item.getReceivedQuantity(), item.getReturnedQuantity(),
                    outstanding, item.getUnitCost(), item.getLineTotal()
            );
        }
    }
}
package nmk_backend.dto;

import nmk_backend.entity.InventoryBalance;

public record LowStockAlertResponse(
        Long locationId,
        String locationName,
        Long variantId,
        String sku,
        String productName,
        String variantName,
        int onHandQuantity,
        int reservedQuantity,
        int availableQuantity,
        int reorderLevel
) {
    public static LowStockAlertResponse from(InventoryBalance balance) {
        return new LowStockAlertResponse(
                balance.getLocation().getId(),
                balance.getLocation().getName(),
                balance.getVariant().getId(),
                balance.getVariant().getSku(),
                balance.getVariant().getProduct().getName(),
                balance.getVariant().getVariantName(),
                balance.getOnHandQuantity(),
                balance.getReservedQuantity(),
                balance.getAvailableQuantity(),
                balance.getReorderLevel()
        );
    }
}
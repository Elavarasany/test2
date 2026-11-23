package nmk_backend.dto;

import nmk_backend.entity.InventoryBalance;

public record InventoryBalanceResponse(
        Long locationId,
        Long variantId,
        int onHandQuantity,
        int reservedQuantity,
        int availableQuantity,
        int reorderLevel
) {
    public static InventoryBalanceResponse from(InventoryBalance balance) {
        return new InventoryBalanceResponse(
                balance.getLocation().getId(),
                balance.getVariant().getId(),
                balance.getOnHandQuantity(),
                balance.getReservedQuantity(),
                balance.getAvailableQuantity(),
                balance.getReorderLevel()
        );
    }
}

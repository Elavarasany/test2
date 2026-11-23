package nmk_backend.dto;

import nmk_backend.entity.StockMovement;

import java.time.LocalDateTime;

public record InventoryMovementResponse(
        Long id,
        Long locationId,
        Long variantId,
        String productName,
        String variantName,
        String sku,
        String movementType,
        Integer quantityDelta,
        Integer reservedDelta,
        Integer onHandAfter,
        Integer reservedAfter,
        String referenceType,
        Long referenceId,
        Long actorStaffId,
        Long actorAdminId,
        String reason,
        LocalDateTime createdAt
) {
    public static InventoryMovementResponse from(StockMovement movement) {
        var variant = movement.getVariant();
        return new InventoryMovementResponse(
                movement.getId(),
                movement.getLocation().getId(),
                variant.getId(),
                variant.getProduct().getName(),
                variant.getVariantName(),
                variant.getSku(),
                movement.getMovementType(),
                movement.getQuantityDelta(),
                movement.getReservedDelta(),
                movement.getOnHandAfter(),
                movement.getReservedAfter(),
                movement.getReferenceType(),
                movement.getReferenceId(),
                movement.getActorStaffId(),
                movement.getActorAdminId(),
                movement.getReason(),
                movement.getCreatedAt()
        );
    }
}

package nmk_backend.dto;

import nmk_backend.entity.InventoryLocation;

public record PosLocationResponse(
        Long id,
        String code,
        String name,
        boolean stockCountLocked
) {
    public static PosLocationResponse from(InventoryLocation location) {
        return new PosLocationResponse(
                location.getId(),
                location.getCode(),
                location.getName(),
                location.isStockCountLocked()
        );
    }
}
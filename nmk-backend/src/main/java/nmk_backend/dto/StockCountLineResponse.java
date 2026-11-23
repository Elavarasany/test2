package nmk_backend.dto;

import nmk_backend.entity.StockCountLine;

public record StockCountLineResponse(
        Long id,
        Long variantId,
        String sku,
        String productName,
        String variantName,
        Integer snapshotOnHand,
        Integer countedQuantity,
        Integer variance,
        String countedByUsername
) {
    public static StockCountLineResponse from(StockCountLine line) {
        Integer variance = line.getCountedQuantity() == null
                ? null : line.getCountedQuantity() - line.getSnapshotOnHand();
        return new StockCountLineResponse(
                line.getId(),
                line.getVariant().getId(),
                line.getVariant().getSku(),
                line.getVariant().getProduct().getName(),
                line.getVariant().getVariantName(),
                line.getSnapshotOnHand(),
                line.getCountedQuantity(),
                variance,
                line.getCountedByUsername()
        );
    }
}
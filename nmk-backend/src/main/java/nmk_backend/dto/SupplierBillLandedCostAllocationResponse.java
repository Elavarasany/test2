package nmk_backend.dto;

import java.math.BigDecimal;

public record SupplierBillLandedCostAllocationResponse(
        Long id,
        Long billId,
        Long goodsReceiptItemId,
        Long purchaseOrderItemId,
        Long variantId,
        String sku,
        int receivedQuantity,
        BigDecimal baseGoodsValue,
        BigDecimal allocatedAmount,
        BigDecimal landedUnitCost
) {}

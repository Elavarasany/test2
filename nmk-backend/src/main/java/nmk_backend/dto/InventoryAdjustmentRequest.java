package nmk_backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class InventoryAdjustmentRequest {

    @NotNull
    @Positive
    private Long locationId;

    @NotNull
    @Positive
    private Long variantId;

    @NotNull
    @Min(-1000000)
    @Max(1000000)
    private Integer quantityDelta;

    @NotBlank
    @Size(max = 150)
    private String idempotencyKey;

    @NotBlank
    @Size(max = 500)
    private String reason;

    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }

    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }

    public Integer getQuantityDelta() { return quantityDelta; }
    public void setQuantityDelta(Integer quantityDelta) { this.quantityDelta = quantityDelta; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

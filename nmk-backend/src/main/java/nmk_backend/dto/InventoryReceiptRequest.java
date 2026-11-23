package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class InventoryReceiptRequest {

    @NotNull
    @Positive
    private Long locationId;

    @NotNull
    @Positive
    private Long variantId;

    @NotNull
    @Positive
    private Integer quantity;

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

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

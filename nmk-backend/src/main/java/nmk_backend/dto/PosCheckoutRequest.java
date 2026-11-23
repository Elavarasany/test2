
package nmk_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public class PosCheckoutRequest {

    @NotNull
    @Positive
    private Long locationId;

    @NotNull
    @Positive
    private Long shiftId;

    @Size(max = 150)
    private String customerName;

    @Size(max = 30)
    private String customerPhone;

    @NotNull
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @NotBlank
    @Size(max = 100)
    private String idempotencyKey;

    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<@NotNull @Valid PosSaleItemRequest> items;

    @NotEmpty
    @Size(max = 3)
    @Valid
    private List<@NotNull @Valid PosPaymentRequest> payments;

    public Long getLocationId() {
        return locationId;
    }

    public void setLocationId(Long locationId) {
        this.locationId = locationId;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public void setShiftId(Long shiftId) {
        this.shiftId = shiftId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public List<PosSaleItemRequest> getItems() {
        return items;
    }

    public void setItems(List<PosSaleItemRequest> items) {
        this.items = items;
    }

    public List<PosPaymentRequest> getPayments() {
        return payments;
    }

    public void setPayments(List<PosPaymentRequest> payments) {
        this.payments = payments;
    }
}

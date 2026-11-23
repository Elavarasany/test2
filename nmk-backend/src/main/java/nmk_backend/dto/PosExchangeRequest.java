package nmk_backend.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
public record PosExchangeRequest(
 @NotNull @Positive Long shiftId, @NotBlank @Size(max=500) String reason,
 @NotBlank @Size(max=100) String idempotencyKey,
 @NotNull @Size(min=1,max=100) List<@Valid PosReturnItemRequest> returnItems,
 @NotNull @Size(min=1,max=100) List<@Valid PosSaleItemRequest> replacementItems,
 @NotNull BigDecimal replacementDiscountAmount,
 @Size(max=3) List<@Valid PosPaymentRequest> collectionPayments,
 @Size(max=3) List<@Valid PosRefundPaymentRequest> refundPayments
) {}
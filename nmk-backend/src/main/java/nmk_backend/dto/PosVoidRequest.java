package nmk_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PosVoidRequest(
        @NotNull @Positive Long shiftId,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @Size(max = 3) List<@Valid PosRefundPaymentRequest> refundPayments
) {
}
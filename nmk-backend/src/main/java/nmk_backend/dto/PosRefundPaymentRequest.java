package nmk_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PosRefundPaymentRequest(
        @NotBlank String paymentMethod,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 150) String transactionReference
) {
}
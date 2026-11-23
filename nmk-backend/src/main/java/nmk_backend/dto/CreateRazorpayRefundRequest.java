package nmk_backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRazorpayRefundRequest(
        @NotNull @Min(100) Long amountPaise,
        @NotBlank @Size(max = 255) String reason,
        @NotBlank @Size(min = 10, max = 80)
        @Pattern(regexp = "[A-Za-z0-9_-]+") String idempotencyKey
) {}

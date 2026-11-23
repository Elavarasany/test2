package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDeliveryDetailsRequest(
        @NotBlank
        @Size(max = 100)
        String courierName,

        @NotBlank
        @Size(max = 150)
        String trackingNumber
) {}
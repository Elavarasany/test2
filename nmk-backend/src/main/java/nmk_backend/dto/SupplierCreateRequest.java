package nmk_backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierCreateRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 120) String contactName,
        @Size(max = 30) String phone,
        @Email @Size(max = 160) String email,
        @Size(max = 500) String address
) {
}
package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StaffAccountCreateRequest(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Username may contain letters, numbers, dot, underscore and hyphen")
        String username,

        @NotBlank
        @Size(min = 12, max = 72, message = "Password must contain 12 to 72 characters")
        String password,

        @NotBlank
        @Pattern(regexp = "^(ADMIN|MANAGER|CASHIER)$", message = "Role must be ADMIN, MANAGER or CASHIER")
        String role
) {
}
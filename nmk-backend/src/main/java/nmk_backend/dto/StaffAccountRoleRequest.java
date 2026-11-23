package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StaffAccountRoleRequest(
        @NotBlank
        @Pattern(regexp = "^(ADMIN|MANAGER|CASHIER)$", message = "Role must be ADMIN, MANAGER or CASHIER")
        String role
) {
}
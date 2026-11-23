package nmk_backend.dto;

import nmk_backend.entity.Admin;

import java.time.LocalDateTime;

public record StaffAccountResponse(
        Long id,
        String username,
        String role,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static StaffAccountResponse from(Admin admin) {
        return new StaffAccountResponse(
                admin.getId(),
                admin.getUsername(),
                admin.getRole(),
                admin.isEnabled(),
                admin.getCreatedAt()
        );
    }
}
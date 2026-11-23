package nmk_backend.dto;

import nmk_backend.entity.Supplier;
import java.time.LocalDateTime;

public record SupplierResponse(
        Long id, String name, String contactName, String phone,
        String email, String address, boolean active, LocalDateTime createdAt
) {
    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(), supplier.getName(), supplier.getContactName(),
                supplier.getPhone(), supplier.getEmail(), supplier.getAddress(),
                supplier.isActive(), supplier.getCreatedAt()
        );
    }
}
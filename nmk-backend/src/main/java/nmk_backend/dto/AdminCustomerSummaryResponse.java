package nmk_backend.dto;

import nmk_backend.repository.CustomerOrderSummaryProjection;
import java.math.BigDecimal;

public record AdminCustomerSummaryResponse(
        String customerName,
        String email,
        String phone,
        Long totalOrders,
        BigDecimal totalSpent
) {
    public static AdminCustomerSummaryResponse from(CustomerOrderSummaryProjection projection) {
        return new AdminCustomerSummaryResponse(
                projection.getCustomerName(),
                projection.getEmail(),
                projection.getPhone(),
                projection.getTotalOrders(),
                projection.getTotalSpent() == null ? BigDecimal.ZERO : projection.getTotalSpent()
        );
    }
}

package nmk_backend.repository;

import java.math.BigDecimal;

public interface CustomerOrderSummaryProjection {
    String getCustomerKey();
    String getCustomerName();
    String getEmail();
    String getPhone();
    Long getTotalOrders();
    BigDecimal getTotalSpent();
}

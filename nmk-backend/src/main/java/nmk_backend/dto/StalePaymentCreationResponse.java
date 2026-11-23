package nmk_backend.dto;

import nmk_backend.entity.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public record StalePaymentCreationResponse(
        Long orderId,
        String razorpayOrderId,
        BigDecimal totalAmount,
        String paymentStatus,
        String paymentCreationStatus,
        String orderStatus,
        boolean stockReserved,
        Long inventoryLocationId,
        LocalDateTime orderDate,
        LocalDateTime reservationExpiresAt,
        long ageMinutes
) {
    public static StalePaymentCreationResponse from(Order order, LocalDateTime now) {
        LocalDateTime createdAt = order.getOrderDate();
        long age = createdAt == null ? 0 : Math.max(0, ChronoUnit.MINUTES.between(createdAt, now));
        return new StalePaymentCreationResponse(
                order.getId(),
                order.getRazorpayOrderId(),
                order.getTotalAmount(),
                order.getPaymentStatus(),
                order.getPaymentCreationStatus(),
                order.getOrderStatus(),
                order.isStockReserved(),
                order.getInventoryLocationId(),
                createdAt,
                order.getReservationExpiresAt(),
                age
        );
    }
}

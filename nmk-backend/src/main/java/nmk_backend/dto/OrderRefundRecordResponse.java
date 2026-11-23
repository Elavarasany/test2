package nmk_backend.dto;

import java.time.LocalDateTime;

public record OrderRefundRecordResponse(
        Long id,
        Long orderId,
        String razorpayRefundId,
        String razorpayPaymentId,
        long amountPaise,
        String currency,
        String status,
        String receipt,
        String reason,
        String initiatedBy,
        String requestIdempotencyKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

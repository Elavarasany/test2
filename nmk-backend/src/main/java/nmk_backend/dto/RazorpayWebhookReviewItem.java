package nmk_backend.dto;

import java.time.LocalDateTime;

public record RazorpayWebhookReviewItem(
        Long id,
        String eventId,
        String eventType,
        String status,
        LocalDateTime receivedAt,
        int attemptCount,
        String processingError,
        String resourceId,
        String paymentId,
        String orderId,
        Long amountPaise,
        String currency,
        String providerStatus
) {
}

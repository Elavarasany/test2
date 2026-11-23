package nmk_backend.dto;

public record RazorpayRefundInitiationResponse(
        Long localRefundId,
        Long orderId,
        String razorpayRefundId,
        long amountPaise,
        String currency,
        String status,
        String receipt,
        boolean retryWithSameKey,
        String message
) {}

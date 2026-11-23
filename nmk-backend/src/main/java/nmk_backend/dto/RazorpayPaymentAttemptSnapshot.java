package nmk_backend.dto;

public record RazorpayPaymentAttemptSnapshot(
        String paymentId,
        String status,
        Integer amountPaise,
        String currency,
        Boolean captured
) {}

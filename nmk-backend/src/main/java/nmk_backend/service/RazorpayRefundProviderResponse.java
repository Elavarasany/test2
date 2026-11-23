package nmk_backend.service;

public record RazorpayRefundProviderResponse(
        String refundId,
        String paymentId,
        long amountPaise,
        String currency,
        String status,
        String receipt
) {}

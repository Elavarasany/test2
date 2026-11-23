package nmk_backend.service;

public interface RazorpayRefundGateway {
    RazorpayRefundProviderResponse createNormalRefund(
            String paymentId,
            long amountPaise,
            String receipt,
            String idempotencyKey,
            String reason,
            Long orderId
    );
}

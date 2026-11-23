package nmk_backend.dto;

import java.util.List;

public record RazorpayOrderSnapshot(
        String orderId,
        String status,
        Integer amountPaise,
        Integer amountPaidPaise,
        Integer amountDuePaise,
        String currency,
        Integer attempts,
        List<RazorpayPaymentAttemptSnapshot> payments
) {}

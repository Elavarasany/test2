package nmk_backend.dto;

import java.time.LocalDateTime;
import java.util.List;

public record StalePaymentProviderReviewResponse(
        Long localOrderId,
        String razorpayOrderId,
        String localPaymentStatus,
        String localPaymentCreationStatus,
        boolean stockReserved,
        LocalDateTime reservationExpiresAt,
        String providerOrderStatus,
        Integer providerAmountPaise,
        Integer providerAmountPaidPaise,
        Integer providerAmountDuePaise,
        String providerCurrency,
        Integer providerAttempts,
        boolean providerAmountMatchesLocal,
        List<RazorpayPaymentAttemptSnapshot> paymentAttempts,
        String reviewWarning,
        LocalDateTime checkedAt
) {}

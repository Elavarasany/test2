package nmk_backend.service;

import nmk_backend.dto.StalePaymentCreationResponse;
import nmk_backend.dto.RazorpayOrderSnapshot;
import nmk_backend.dto.StalePaymentProviderReviewResponse;
import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class StalePaymentCreationReviewService {

    private final OrderRepository orderRepository;
    private final RazorpayOrderStatusGateway providerGateway;

    public StalePaymentCreationReviewService(OrderRepository orderRepository,
                                             RazorpayOrderStatusGateway providerGateway) {
        this.orderRepository = orderRepository;
        this.providerGateway = providerGateway;
    }

    // Do not hold a database transaction/connection while making the provider network calls.
    public StalePaymentProviderReviewResponse getProviderStatus(Long localOrderId) {
        if (localOrderId == null || localOrderId <= 0) {
            throw new IllegalArgumentException("A valid local order ID is required");
        }

        Order localOrder = orderRepository.findById(localOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        LocalDateTime now = LocalDateTime.now();
        if (!localOrder.isStockReserved()
                || !"PENDING".equalsIgnoreCase(localOrder.getPaymentStatus())
                || !"PENDING".equalsIgnoreCase(localOrder.getOrderStatus())
                || localOrder.getReservationExpiresAt() == null
                || localOrder.getReservationExpiresAt().isAfter(now)) {
            throw new IllegalStateException("Only expired, unpaid orders with reserved stock can be reviewed");
        }

        String razorpayOrderId = localOrder.getRazorpayOrderId();
        if (razorpayOrderId == null || razorpayOrderId.isBlank()) {
            throw new IllegalStateException("No Razorpay order ID is recorded for this local order");
        }

        final RazorpayOrderSnapshot provider;
        try {
            provider = providerGateway.fetchOrderAndPayments(razorpayOrderId);
        } catch (Exception exception) {
            throw new ProviderStatusUnavailableException();
        }

        if (provider == null || !razorpayOrderId.equals(provider.orderId())) {
            throw new ProviderStatusUnavailableException();
        }

        Integer expectedPaise = null;
        if (localOrder.getTotalAmount() != null) {
            try {
                expectedPaise = localOrder.getTotalAmount()
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.UNNECESSARY)
                        .intValueExact();
            } catch (ArithmeticException ignored) {
                expectedPaise = null;
            }
        }

        boolean amountMatches = expectedPaise != null
                && provider.amountPaise() != null
                && expectedPaise.equals(provider.amountPaise())
                && "INR".equalsIgnoreCase(provider.currency());

        return new StalePaymentProviderReviewResponse(
                localOrder.getId(),
                razorpayOrderId,
                localOrder.getPaymentStatus(),
                localOrder.getPaymentCreationStatus(),
                localOrder.isStockReserved(),
                localOrder.getReservationExpiresAt(),
                provider.status(),
                provider.amountPaise(),
                provider.amountPaidPaise(),
                provider.amountDuePaise(),
                provider.currency(),
                provider.attempts(),
                amountMatches,
                provider.payments(),
                "Provider status is informational only. This lookup does not cancel the Razorpay order, release stock, or change the local order.",
                now
        );
    }

    public static class ProviderStatusUnavailableException extends RuntimeException {
        public ProviderStatusUnavailableException() {
            super("Razorpay status could not be verified. No local data was changed.");
        }
    }

    @Transactional(readOnly = true)
    public Page<StalePaymentCreationResponse> getStalePaymentCreations(
            int page, int size, int olderThanMinutes
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        if (olderThanMinutes < 1 || olderThanMinutes > 10080) {
            throw new IllegalArgumentException("olderThanMinutes must be between 1 and 10080");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(olderThanMinutes);
        return orderRepository
                .findExpiredStalePaymentCreations(
                        java.util.List.of("IN_PROGRESS", "CREATED"),
                        "PENDING",
                        cutoff,
                        now,
                        PageRequest.of(page, size, Sort.by(Sort.Order.asc("orderDate"), Sort.Order.asc("id")))
                )
                .map(order -> StalePaymentCreationResponse.from(order, now));
    }
}

package nmk_backend.service;

import nmk_backend.dto.OrderRefundRecordResponse;
import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RazorpayRefundRequestService {
    private static final Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]+");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{10,80}");

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;

    public RazorpayRefundRequestService(
            OrderRepository orderRepository,
            OrderRefundRepository refundRepository
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional
    public PreparedRefund prepare(Long orderId, Long amountPaise, String reason,
                                  String idempotencyKey, String username) {
        if (orderId == null || orderId <= 0
                || amountPaise == null || amountPaise < 100
                || reason == null || reason.isBlank() || reason.trim().length() > 255
                || idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Refund order, amount, reason and idempotency key are required");
        }

        String cleanReason = reason.trim();
        // Lock the order first. Querying a missing idempotency-key row first can
        // acquire InnoDB next-key gap locks and deadlock two distinct refund requests
        // that are competing for the same order.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        // Every refund request for this order now follows the same lock order.
        Optional<OrderRefund> existing =
                refundRepository.findByRequestIdempotencyKeyForUpdate(idempotencyKey);
        if (existing.isPresent()) {
            return reuseExisting(existing.get(), orderId, amountPaise, cleanReason);
        }

        if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new IllegalArgumentException("Only captured and paid Razorpay orders can be refunded");
        }
        String paymentId = order.getRazorpayPaymentId();
        if (paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()) {
            throw new IllegalArgumentException("This order has no verified Razorpay payment to refund");
        }
        boolean anotherPendingRefund = refundRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream().anyMatch(refund -> "PENDING".equalsIgnoreCase(refund.getStatus()));
        if (anotherPendingRefund) {
            throw new IllegalStateException(
                    "Another refund is pending for this order. Wait for Razorpay confirmation before starting another.");
        }

        long orderAmountPaise;
        try {
            orderAmountPaise = order.getTotalAmount()
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (Exception invalidAmount) {
            throw new IllegalArgumentException("The original order amount cannot be verified");
        }

        long activeRefundTotal = Optional.ofNullable(
                refundRepository.sumActiveAmountPaiseByOrderId(orderId)
        ).orElse(0L);
        if (amountPaise > orderAmountPaise - activeRefundTotal) {
            throw new IllegalArgumentException("Refund amount exceeds the remaining refundable balance");
        }

        String receipt = "nmk_" + UUID.randomUUID().toString().replace("-", "");
        OrderRefund refund = new OrderRefund();
        refund.setOrder(order);
        refund.setRazorpayPaymentId(paymentId);
        refund.setAmountPaise(amountPaise);
        refund.setCurrency("INR");
        refund.setStatus("PENDING");
        refund.setRequestIdempotencyKey(idempotencyKey);
        refund.setReceipt(receipt);
        refund.setReason(cleanReason);
        refund.setInitiatedBy(username.trim());

        OrderRefund saved = refundRepository.saveAndFlush(refund);
        order.setRefundStatus("PENDING");
        orderRepository.save(order);

        return toPrepared(saved, order, true);
    }

    @Transactional(readOnly = true)
    public PreparedRefund getByIdempotencyKey(String idempotencyKey) {
        OrderRefund refund = refundRepository.findByRequestIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("Refund request was not found"));
        return toPrepared(refund, refund.getOrder(), false);
    }

    @Transactional(readOnly = true)
    public List<OrderRefundRecordResponse> listForOrder(Long orderId) {
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("Invalid order ID");
        if (!orderRepository.existsById(orderId)) throw new IllegalArgumentException("Order not found");
        return refundRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(refund -> new OrderRefundRecordResponse(
                        refund.getId(),
                        refund.getOrder().getId(),
                        refund.getRazorpayRefundId(),
                        refund.getRazorpayPaymentId(),
                        refund.getAmountPaise(),
                        refund.getCurrency(),
                        refund.getStatus(),
                        refund.getReceipt(),
                        refund.getReason(),
                        refund.getInitiatedBy(),
                        refund.getRequestIdempotencyKey(),
                        refund.getCreatedAt(),
                        refund.getUpdatedAt()
                ))
                .toList();
    }

    private PreparedRefund reuseExisting(OrderRefund refund, Long orderId, Long amountPaise, String reason) {
        if (!refund.getOrder().getId().equals(orderId)
                || refund.getAmountPaise() != amountPaise
                || !reason.equals(refund.getReason())) {
            throw new IllegalArgumentException(
                    "This idempotency key is already bound to a different refund request");
        }
        if ("FAILED".equals(refund.getStatus())) {
            throw new IllegalArgumentException(
                    "This refund request was rejected. Create a new idempotency key for a new attempt");
        }
        return toPrepared(refund, refund.getOrder(), false);
    }

    private PreparedRefund toPrepared(OrderRefund refund, Order order, boolean newlyCreated) {
        return new PreparedRefund(
                refund.getId(),
                order.getId(),
                refund.getRazorpayPaymentId(),
                refund.getAmountPaise(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getReceipt(),
                refund.getReason(),
                refund.getRequestIdempotencyKey(),
                refund.getRazorpayRefundId(),
                newlyCreated
        );
    }

    public record PreparedRefund(
            Long localRefundId,
            Long orderId,
            String paymentId,
            long amountPaise,
            String currency,
            String status,
            String receipt,
            String reason,
            String idempotencyKey,
            String providerRefundId,
            boolean newlyCreated
    ) {}
}

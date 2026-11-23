package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class RazorpayRefundReconciliationService {
    private static final Pattern REFUND_ID = Pattern.compile("rfnd_[A-Za-z0-9]+");
    private static final Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]+");

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;

    public RazorpayRefundReconciliationService(
            OrderRepository orderRepository,
            OrderRefundRepository refundRepository
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reconcile(
            String providerEventId,
            String eventType,
            String refundId,
            String paymentId,
            long amountPaise,
            String currency,
            String providerStatus
    ) {
        reconcile(providerEventId, eventType, refundId, paymentId, amountPaise,
                currency, providerStatus, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reconcile(
            String providerEventId,
            String eventType,
            String refundId,
            String paymentId,
            long amountPaise,
            String currency,
            String providerStatus,
            String receipt
    ) {
        if (providerEventId == null || providerEventId.isBlank()
                || eventType == null
                || refundId == null || !REFUND_ID.matcher(refundId).matches()
                || paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()
                || amountPaise <= 0
                || (currency != null && !currency.isBlank() && !"INR".equalsIgnoreCase(currency))
                || (receipt != null && (receipt.length() > 64 || receipt.chars().anyMatch(Character::isISOControl)))) {
            throw new IllegalArgumentException("Razorpay refund payload is incomplete or invalid");
        }

        String targetStatus = expectedStatus(eventType, providerStatus);
        Order order = orderRepository.findByRazorpayPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No paid local order matches the Razorpay refund payment"
                ));

        if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())
                && !"REFUNDED".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new IllegalArgumentException(
                    "Refund event references an order that is not recorded as paid"
            );
        }

        long orderAmountPaise = toPaise(order);
        boolean mergedReceiptDuplicate = false;
        Optional<OrderRefund> existing = refundRepository.findByRazorpayRefundIdForUpdate(refundId);
        if (existing.isPresent() && receipt != null && !receipt.isBlank()) {
            OrderRefund providerRow = existing.get();
            Optional<OrderRefund> receiptRequest =
                    refundRepository.findByRazorpayPaymentIdAndReceiptForUpdate(paymentId, receipt);
            if (receiptRequest.isPresent() && receiptRequest.get() != providerRow) {
                OrderRefund localRequest = receiptRequest.get();
                if (!order.getId().equals(localRequest.getOrder().getId())
                        || !paymentId.equals(localRequest.getRazorpayPaymentId())
                        || localRequest.getAmountPaise() != amountPaise
                        || !"INR".equalsIgnoreCase(localRequest.getCurrency())
                        || !"PENDING".equals(localRequest.getStatus())
                        || localRequest.getRequestIdempotencyKey() == null
                        || localRequest.getRazorpayRefundId() != null) {
                    throw new IllegalArgumentException(
                            "Razorpay refund receipt conflicts with its reserved local request");
                }

                // A webhook without receipt may have created a second provider row.
                // When the API response later supplies the receipt, retain the
                // original idempotency row and merge the provider state into it.
                String existingProviderStatus = providerRow.getStatus();
                String existingProviderEventId = providerRow.getLastProviderEventId();
                refundRepository.delete(providerRow);
                refundRepository.flush();
                localRequest.setRazorpayRefundId(refundId);
                localRequest.setStatus(existingProviderStatus);
                localRequest.setLastProviderEventId(existingProviderEventId);
                existing = Optional.of(localRequest);
                mergedReceiptDuplicate = true;
            }
        }
        if (existing.isEmpty() && receipt != null && !receipt.isBlank()) {
            existing = refundRepository.findByRazorpayPaymentIdAndReceiptForUpdate(paymentId, receipt);
        }

        OrderRefund refund;
        if (existing.isPresent()) {
            refund = existing.get();
            if (!refund.getOrder().getId().equals(order.getId())
                    || !paymentId.equals(refund.getRazorpayPaymentId())
                    || refund.getAmountPaise() != amountPaise
                    || !"INR".equalsIgnoreCase(refund.getCurrency())
                    || (refund.getRazorpayRefundId() != null
                    && !refundId.equals(refund.getRazorpayRefundId()))
                    || (receipt != null && !receipt.isBlank()
                    && refund.getReceipt() != null && !receipt.equals(refund.getReceipt()))) {
                throw new IllegalArgumentException(
                        "Razorpay refund ID conflicts with its previously recorded order, receipt or amount"
                );
            }

            String currentStatus = refund.getStatus();
            boolean terminalStatus = "PROCESSED".equals(currentStatus) || "FAILED".equals(currentStatus);
            if ("PROCESSED".equals(currentStatus)) {
                if (!"PROCESSED".equals(targetStatus) && !"PENDING".equals(targetStatus)) {
                    throw new IllegalArgumentException("A processed refund cannot be changed to failed");
                }
                if (!mergedReceiptDuplicate) return;
            }
            if ("FAILED".equals(currentStatus)) {
                if (!"FAILED".equals(targetStatus) && !"PENDING".equals(targetStatus)) {
                    throw new IllegalArgumentException("A failed refund cannot later be marked processed");
                }
                if (!mergedReceiptDuplicate) return;
            }

            refund.setRazorpayRefundId(refundId);
            if (receipt != null && !receipt.isBlank()) refund.setReceipt(receipt);
            if (!terminalStatus) {
                refund.setStatus(targetStatus);
                refund.setLastProviderEventId(providerEventId);
            }
        } else {
            refund = new OrderRefund();
            refund.setOrder(order);
            refund.setRazorpayRefundId(refundId);
            refund.setRazorpayPaymentId(paymentId);
            refund.setAmountPaise(amountPaise);
            refund.setCurrency("INR");
            refund.setReceipt(receipt == null || receipt.isBlank() ? null : receipt);
            refund.setStatus(targetStatus);
            refund.setLastProviderEventId(providerEventId);
        }

        refundRepository.saveAndFlush(refund);
        recalculateOrderRefundStatus(order, orderAmountPaise);
        orderRepository.save(order);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markInitiationFailed(String idempotencyKey) {
        OrderRefund snapshot = refundRepository.findByRequestIdempotencyKey(idempotencyKey)
                .orElse(null);
        if (snapshot == null) return;

        // Keep the same lock order as webhook reconciliation: order first, then refund.
        Order order = orderRepository.findByIdForUpdate(snapshot.getOrder().getId())
                .orElseThrow(() -> new IllegalStateException("Refund order was not found"));
        OrderRefund refund = refundRepository.findByRequestIdempotencyKeyForUpdate(idempotencyKey)
                .orElse(null);
        if (refund == null || !"PENDING".equals(refund.getStatus())
                || refund.getRazorpayRefundId() != null) {
            return;
        }
        refund.setStatus("FAILED");
        refund.setLastProviderEventId("API_REJECTED");
        refundRepository.saveAndFlush(refund);
        recalculateOrderRefundStatus(order, toPaise(order));
        orderRepository.save(order);
    }

    private void recalculateOrderRefundStatus(Order order, long orderAmountPaise) {
        long activeRefundTotal = Optional.ofNullable(
                refundRepository.sumActiveAmountPaiseByOrderId(order.getId())
        ).orElse(0L);
        long processedRefundTotal = Optional.ofNullable(
                refundRepository.sumProcessedAmountPaiseByOrderId(order.getId())
        ).orElse(0L);

        if (activeRefundTotal > orderAmountPaise || processedRefundTotal > orderAmountPaise) {
            throw new IllegalArgumentException("Active Razorpay refunds exceed the original captured order amount");
        }

        if (processedRefundTotal == orderAmountPaise) {
            order.setRefundStatus("REFUNDED");
            order.setPaymentStatus("REFUNDED");
        } else if (activeRefundTotal > processedRefundTotal) {
            order.setRefundStatus("PENDING");
            order.setPaymentStatus("PAID");
        } else if (processedRefundTotal > 0) {
            order.setRefundStatus("PARTIALLY_REFUNDED");
            order.setPaymentStatus("PAID");
        } else {
            order.setRefundStatus("FAILED");
            order.setPaymentStatus("PAID");
        }
    }

    private long toPaise(Order order) {
        try {
            return order.getTotalAmount()
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (Exception invalidAmount) {
            throw new IllegalArgumentException("Local order amount cannot be verified");
        }
    }

    private String expectedStatus(String eventType, String providerStatus) {
        String normalizedType = eventType.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = providerStatus == null
                ? "" : providerStatus.trim().toLowerCase(Locale.ROOT);

        return switch (normalizedType) {
            case "refund.created" -> {
                if (!"pending".equals(normalizedStatus) && !"created".equals(normalizedStatus)) {
                    throw new IllegalArgumentException("Razorpay refund.created status is not pending");
                }
                yield "PENDING";
            }
            case "refund.processed" -> {
                if (!"processed".equals(normalizedStatus)) {
                    throw new IllegalArgumentException("Razorpay refund.processed status is not processed");
                }
                yield "PROCESSED";
            }
            case "refund.failed" -> {
                if (!"failed".equals(normalizedStatus)) {
                    throw new IllegalArgumentException("Razorpay refund.failed status is not failed");
                }
                yield "FAILED";
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported Razorpay refund event requires manual review"
            );
        };
    }
}

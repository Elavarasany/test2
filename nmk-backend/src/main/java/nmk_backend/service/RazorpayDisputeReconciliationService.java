package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.entity.RazorpayDispute;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayDisputeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class RazorpayDisputeReconciliationService {
    private static final Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]+");
    private static final Pattern DISPUTE_ID = Pattern.compile("disp_[A-Za-z0-9]+");
    private static final Set<String> ALLOWED_STATUSES =
            Set.of("OPEN", "UNDER_REVIEW", "ACTION_REQUIRED", "WON", "LOST", "CLOSED", "UNKNOWN");
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private final RazorpayDisputeRepository disputeRepository;
    private final OrderRepository orderRepository;

    public RazorpayDisputeReconciliationService(
            RazorpayDisputeRepository disputeRepository,
            OrderRepository orderRepository
    ) {
        this.disputeRepository = disputeRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void reconcile(
            String providerEventId,
            String eventType,
            String disputeId,
            String paymentId,
            long amountPaise,
            String currency,
            long amountDeductedPaise,
            String reasonCode,
            String reasonDescription,
            Long respondByEpoch,
            String providerStatus,
            String phase,
            Long providerCreatedAtEpoch,
            Long providerEventAtEpoch
    ) {
        if (providerEventId == null || providerEventId.isBlank() || providerEventId.length() > 150
                || eventType == null || !eventType.startsWith("payment.dispute.")
                || disputeId == null || !DISPUTE_ID.matcher(disputeId).matches()
                || paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()) {
            throw new IllegalArgumentException("Razorpay dispute identifiers are invalid");
        }
        if (amountPaise <= 0 || amountDeductedPaise < 0 || amountDeductedPaise > amountPaise) {
            throw new IllegalArgumentException("Razorpay dispute amount is invalid");
        }

        String normalizedCurrency = currency == null || currency.isBlank()
                ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
        if (normalizedCurrency.length() != 3) {
            throw new IllegalArgumentException("Razorpay dispute currency is invalid");
        }

        // Keep the same lock order as refund and payment mutation paths:
        // local order first, provider dispute row second.
        Order order = orderRepository.findByRazorpayPaymentIdForUpdate(paymentId).orElse(null);
        RazorpayDispute dispute = disputeRepository
                .findByProviderDisputeIdForUpdate(disputeId)
                .orElseGet(RazorpayDispute::new);

        if (dispute.getProviderDisputeId() != null
                && (!paymentId.equals(dispute.getProviderPaymentId())
                || dispute.getAmountPaise() != amountPaise
                || !normalizedCurrency.equalsIgnoreCase(dispute.getCurrency()))) {
            throw new IllegalArgumentException(
                    "Razorpay dispute ID was reused with conflicting payment details");
        }

        String nextStatus = normalizeStatus(providerStatus, eventType);
        LocalDateTime eventAt = toIndiaTime(providerEventAtEpoch);
        if (eventAt == null) eventAt = toIndiaTime(providerCreatedAtEpoch);
        if (eventAt == null) eventAt = LocalDateTime.now(INDIA_ZONE);

        if (dispute.getLastProviderEventAt() != null) {
            if (eventAt.isBefore(dispute.getLastProviderEventAt())) {
                return;
            }
            if (eventAt.isEqual(dispute.getLastProviderEventAt())
                    && priority(nextStatus) < priority(dispute.getStatus())) {
                return;
            }
            if (providerEventId.equals(dispute.getLastProviderEventId())) {
                return;
            }
        }

        dispute.setProviderDisputeId(disputeId);
        dispute.setProviderPaymentId(paymentId);
        dispute.setAmountPaise(amountPaise);
        dispute.setAmountDeductedPaise(amountDeductedPaise);
        dispute.setCurrency(normalizedCurrency);
        dispute.setStatus(nextStatus);
        dispute.setLastProviderEventId(providerEventId);
        dispute.setLastProviderEventType(eventType);
        dispute.setLastProviderEventAt(eventAt);

        if (reasonCode != null && !reasonCode.isBlank()) {
            dispute.setReasonCode(limit(reasonCode, 100));
        }
        if (reasonDescription != null && !reasonDescription.isBlank()) {
            dispute.setReasonDescription(limit(reasonDescription, 500));
        }
        if (phase != null && !phase.isBlank()) {
            dispute.setPhase(limit(phase, 40));
        }
        LocalDateTime respondBy = toIndiaTime(respondByEpoch);
        if (respondBy != null) {
            dispute.setRespondBy(respondBy);
        } else if (Set.of("WON", "LOST", "CLOSED").contains(nextStatus)) {
            dispute.setRespondBy(null);
        }
        LocalDateTime createdAt = toIndiaTime(providerCreatedAtEpoch);
        if (createdAt != null) dispute.setProviderCreatedAt(createdAt);

        dispute.setLocalOrderId(order == null ? null : order.getId());
        if (!"INR".equals(normalizedCurrency)) {
            dispute.setMatchStatus("MISMATCH");
            dispute.setMatchMessage("Dispute currency differs from the NMK INR order ledger.");
        } else if (order == null) {
            dispute.setMatchStatus("UNMATCHED");
            dispute.setMatchMessage("No local order matches this Razorpay payment ID.");
        } else if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())
                && !"REFUNDED".equalsIgnoreCase(order.getPaymentStatus())) {
            dispute.setMatchStatus("MISMATCH");
            dispute.setMatchMessage("Local order is not recorded as paid.");
        } else if (toPaise(order.getTotalAmount()) < amountPaise) {
            dispute.setMatchStatus("MISMATCH");
            dispute.setMatchMessage("Dispute amount exceeds the local order total.");
        } else if ("UNKNOWN".equals(nextStatus)) {
            dispute.setMatchStatus("MANUAL_REVIEW");
            dispute.setMatchMessage("Unrecognized Razorpay dispute status requires manual review.");
        } else {
            dispute.setMatchStatus("MATCHED");
            dispute.setMatchMessage("Dispute payment ID and amount match a local paid order.");
        }

        disputeRepository.saveAndFlush(dispute);
    }

    private String normalizeStatus(String providerStatus, String eventType) {
        String suffix = eventType.substring("payment.dispute.".length())
                .trim().toLowerCase(Locale.ROOT);
        if (Set.of("created", "under_review", "action_required", "won", "lost", "closed")
                .contains(suffix)) {
            return switch (suffix) {
                case "created" -> "OPEN";
                case "under_review" -> "UNDER_REVIEW";
                case "action_required" -> "ACTION_REQUIRED";
                case "won" -> "WON";
                case "lost" -> "LOST";
                case "closed" -> "CLOSED";
                default -> "UNKNOWN";
            };
        }
        String status = providerStatus == null ? "" : providerStatus.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_STATUSES.contains(status) ? status : "UNKNOWN";
    }

    private int priority(String status) {
        return switch (status == null ? "" : status) {
            case "OPEN" -> 1;
            case "ACTION_REQUIRED" -> 2;
            case "UNDER_REVIEW" -> 3;
            case "CLOSED" -> 4;
            case "WON", "LOST" -> 5;
            default -> 0;
        };
    }

    private LocalDateTime toIndiaTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return null;
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), INDIA_ZONE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Razorpay dispute timestamp is invalid");
        }
    }

    private long toPaise(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (Exception exception) {
            return -1L;
        }
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

package nmk_backend.service;

import nmk_backend.dto.CreateRazorpayRefundRequest;
import nmk_backend.dto.RazorpayRefundInitiationResponse;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class RazorpayRefundInitiationService {
    private static final Pattern REFUND_ID = Pattern.compile("rfnd_[A-Za-z0-9]+");

    private final RazorpayService razorpayService;
    private final RazorpayRefundRequestService requestService;
    private final RazorpayRefundGateway gateway;
    private final RazorpayRefundReconciliationService reconciliationService;

    public RazorpayRefundInitiationService(
            RazorpayService razorpayService,
            RazorpayRefundRequestService requestService,
            RazorpayRefundGateway gateway,
            RazorpayRefundReconciliationService reconciliationService
    ) {
        this.razorpayService = razorpayService;
        this.requestService = requestService;
        this.gateway = gateway;
        this.reconciliationService = reconciliationService;
    }

    public RazorpayRefundInitiationResponse initiate(
            Long orderId,
            CreateRazorpayRefundRequest request,
            String username
    ) {
        razorpayService.requirePaymentCredentials();

        RazorpayRefundRequestService.PreparedRefund prepared =
                prepareWithLockRetry(orderId, request, username);

        if (prepared.providerRefundId() != null && !prepared.providerRefundId().isBlank()) {
            return response(prepared, false,
                    "Refund already exists at Razorpay. Its status is reconciled from provider updates.");
        }

        final RazorpayRefundProviderResponse provider;
        try {
            provider = gateway.createNormalRefund(
                    prepared.paymentId(),
                    prepared.amountPaise(),
                    prepared.receipt(),
                    prepared.idempotencyKey(),
                    prepared.reason(),
                    prepared.orderId()
            );
        } catch (RazorpayRefundGatewayException exception) {
            if (exception.getHttpStatus() >= 400
                    && exception.getHttpStatus() < 500
                    && exception.getHttpStatus() != 409) {
                reconciliationService.markInitiationFailed(prepared.idempotencyKey());
                throw new RefundProviderRejectedException(
                        "Razorpay rejected the refund request. No refund was recorded as successful.");
            }
            return response(prepared, true,
                    "Razorpay has not confirmed the result. Retry only with the same idempotency key.");
        } catch (RuntimeException exception) {
            // Network timeouts are ambiguous: the provider may have created the refund.
            // Keep the amount reserved and require a retry with the exact same key.
            return response(prepared, true,
                    "The provider response was not received. Retry only with the same idempotency key.");
        }

        if (!isValidProviderResponse(provider, prepared)) {
            return response(prepared, true,
                    "Razorpay returned an incomplete or mismatched response. The request remains reserved for same-key retry/reconciliation.");
        }

        String status = provider.status().trim().toLowerCase(Locale.ROOT);
        String eventType = switch (status) {
            case "pending", "created" -> "refund.created";
            case "processed" -> "refund.processed";
            case "failed" -> "refund.failed";
            default -> null;
        };
        if (eventType == null) {
            return response(prepared, true,
                    "Razorpay returned an unrecognized refund status. The request remains pending for reconciliation.");
        }

        try {
            reconciliationService.reconcile(
                    "api_" + prepared.idempotencyKey(),
                    eventType,
                    provider.refundId(),
                    provider.paymentId(),
                    provider.amountPaise(),
                    provider.currency() == null || provider.currency().isBlank() ? "INR" : provider.currency(),
                    status,
                    provider.receipt()
            );
        } catch (RuntimeException exception) {
            // Do not release the reserved amount when provider state cannot be safely reconciled.
            return response(prepared, true,
                    "Razorpay may have created the refund, but local reconciliation needs a same-key retry or webhook.");
        }

        RazorpayRefundRequestService.PreparedRefund updated =
                requestService.getByIdempotencyKey(prepared.idempotencyKey());
        return response(updated, false, "Razorpay refund request submitted and recorded.");
    }

    private RazorpayRefundRequestService.PreparedRefund prepareWithLockRetry(
            Long orderId, CreateRazorpayRefundRequest request, String username
    ) {
        PessimisticLockingFailureException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return requestService.prepare(
                        orderId, request.amountPaise(), request.reason(),
                        request.idempotencyKey(), username);
            } catch (PessimisticLockingFailureException exception) {
                lastFailure = exception;
                if (attempt < 2) {
                    try {
                        Thread.sleep(40L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Refund reservation was interrupted. Retry with the same idempotency key.",
                                interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Another refund operation is locking this order. Retry with the same idempotency key.",
                lastFailure);
    }

    private boolean isValidProviderResponse(
            RazorpayRefundProviderResponse response,
            RazorpayRefundRequestService.PreparedRefund expected
    ) {
        return response != null
                && response.refundId() != null
                && REFUND_ID.matcher(response.refundId()).matches()
                && expected.paymentId().equals(response.paymentId())
                && expected.amountPaise() == response.amountPaise()
                && (response.currency() == null || response.currency().isBlank() || "INR".equalsIgnoreCase(response.currency()))
                && expected.receipt().equals(response.receipt())
                && response.status() != null
                && !response.status().isBlank();
    }

    private RazorpayRefundInitiationResponse response(
            RazorpayRefundRequestService.PreparedRefund refund,
            boolean retryWithSameKey,
            String message
    ) {
        return new RazorpayRefundInitiationResponse(
                refund.localRefundId(),
                refund.orderId(),
                refund.providerRefundId(),
                refund.amountPaise(),
                refund.currency(),
                refund.status(),
                refund.receipt(),
                retryWithSameKey,
                message
        );
    }

    public static class RefundProviderRejectedException extends RuntimeException {
        public RefundProviderRejectedException(String message) {
            super(message);
        }
    }
}

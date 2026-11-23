package nmk_backend.service;

import nmk_backend.dto.RazorpayWebhookReviewItem;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RazorpayWebhookReviewService {

    private final RazorpayWebhookEventRepository repository;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookReviewService(
            RazorpayWebhookEventRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<RazorpayWebhookReviewItem> getManualReviewQueue(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }

        return repository.findByStatusOrderByReceivedAtAsc(
                "MANUAL_REVIEW",
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "receivedAt"))
        ).map(this::toReviewItem);
    }

    private RazorpayWebhookReviewItem toReviewItem(RazorpayWebhookEvent event) {
        String resourceId = null;
        String paymentId = null;
        String orderId = null;
        Long amountPaise = null;
        String currency = null;
        String providerStatus = null;

        try {
            JsonNode root = objectMapper.readTree(event.getRawPayload());
            JsonNode payload = root.path("payload");
            JsonNode resource = payload.path("refund").path("entity");
            if (resource.isMissingNode() || resource.isNull()) {
                resource = payload.path("payment").path("entity");
            }

            resourceId = textOrNull(resource.path("id"));
            paymentId = textOrNull(resource.path("payment_id"));
            orderId = textOrNull(resource.path("order_id"));
            currency = textOrNull(resource.path("currency"));
            providerStatus = textOrNull(resource.path("status"));

            JsonNode amount = resource.path("amount");
            if (amount.canConvertToLong()) {
                amountPaise = amount.longValue();
            }
        } catch (Exception ignored) {
            // Keep malformed payloads visible by event metadata without exposing raw JSON.
        }

        return new RazorpayWebhookReviewItem(
                event.getId(),
                event.getEventId(),
                event.getEventType(),
                event.getStatus(),
                event.getReceivedAt(),
                event.getAttemptCount(),
                event.getProcessingError(),
                resourceId,
                paymentId,
                orderId,
                amountPaise,
                currency,
                providerStatus
        );
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }
}

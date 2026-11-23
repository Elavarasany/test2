package nmk_backend.controller;

import nmk_backend.dto.RazorpayDisputeResponse;
import nmk_backend.repository.RazorpayDisputeRepository;
import nmk_backend.entity.RazorpayDispute;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/payments/disputes")
public class RazorpayDisputeController {
    private final RazorpayDisputeRepository disputeRepository;

    public RazorpayDisputeController(RazorpayDisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Page must be non-negative and size must be between 1 and 100"));
        }
        return ResponseEntity.ok(disputeRepository
                .findAllByOrderByRespondByAscProviderCreatedAtDesc(PageRequest.of(page, size))
                .map(this::toResponse));
    }

    private RazorpayDisputeResponse toResponse(RazorpayDispute dispute) {
        return new RazorpayDisputeResponse(
                dispute.getId(),
                dispute.getProviderDisputeId(),
                dispute.getProviderPaymentId(),
                dispute.getLocalOrderId(),
                dispute.getAmountPaise(),
                dispute.getAmountDeductedPaise(),
                dispute.getCurrency(),
                dispute.getReasonCode(),
                dispute.getReasonDescription(),
                dispute.getRespondBy(),
                dispute.getStatus(),
                dispute.getPhase(),
                dispute.getProviderCreatedAt(),
                dispute.getLastProviderEventAt(),
                dispute.getLastProviderEventType(),
                dispute.getMatchStatus(),
                dispute.getMatchMessage(),
                dispute.getLastSyncedAt()
        );
    }
}

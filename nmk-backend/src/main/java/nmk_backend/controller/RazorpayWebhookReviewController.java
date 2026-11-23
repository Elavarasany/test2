package nmk_backend.controller;

import nmk_backend.service.RazorpayWebhookReviewService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/payments/webhook-review")
public class RazorpayWebhookReviewController {

    private final RazorpayWebhookReviewService reviewService;

    public RazorpayWebhookReviewController(RazorpayWebhookReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<?> getManualReviewQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        try {
            Page<?> result = reviewService.getManualReviewQueue(page, size);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}

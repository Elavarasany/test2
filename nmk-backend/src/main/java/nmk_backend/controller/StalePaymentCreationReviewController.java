package nmk_backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.service.AdminAuditEventService;
import nmk_backend.service.StalePaymentCreationReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/pos/payments/stale-creation-review")
public class StalePaymentCreationReviewController {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final StalePaymentCreationReviewService reviewService;
    private final AdminAuditEventService auditService;

    public StalePaymentCreationReviewController(StalePaymentCreationReviewService reviewService,
                                                AdminAuditEventService auditService) {
        this.reviewService = reviewService;
        this.auditService = auditService;
    }

    @GetMapping("/{localOrderId}/provider-status")
    public ResponseEntity<?> getProviderStatus(@PathVariable Long localOrderId,
                                               HttpServletRequest request) {
        try {
            var result = reviewService.getProviderStatus(localOrderId);
            recordProviderLookup(request, 200, null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            recordProviderLookup(request, 400, exception);
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (StalePaymentCreationReviewService.ProviderStatusUnavailableException exception) {
            recordProviderLookup(request, 502, exception);
            return ResponseEntity.status(502).body(Map.of("message", exception.getMessage()));
        } catch (IllegalStateException exception) {
            recordProviderLookup(request, 409, exception);
            return ResponseEntity.status(409).body(Map.of("message", exception.getMessage()));
        } catch (RuntimeException exception) {
            recordProviderLookup(request, 500, exception);
            throw exception;
        }
    }

    @GetMapping
    public ResponseEntity<?> getStalePaymentCreations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "10") int olderThanMinutes
    ) {
        try {
            return ResponseEntity.ok(reviewService.getStalePaymentCreations(page, size, olderThanMinutes));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    private void recordProviderLookup(HttpServletRequest request, int status, Exception exception) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            AdminAuditEvent event = new AdminAuditEvent();
            if (authentication != null && authentication.isAuthenticated()) {
                event.setActorUsername(limit(authentication.getName(), 100));
                event.setActorAuthorities(authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).sorted()
                        .collect(Collectors.joining(",")));
            }
            event.setHttpMethod("GET");
            event.setRequestPath(limit(request.getRequestURI(), 500));
            event.setResponseStatus(status);
            event.setOutcome(status < 400 ? "SUCCESS" : "FAILURE");
            event.setRemoteAddress(limit(request.getRemoteAddr(), 64));
            event.setUserAgent(limit(request.getHeader("User-Agent"), 300));
            String requestId = request.getHeader("X-Request-ID");
            event.setRequestId(requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()
                    ? requestId : null);
            event.setErrorType(exception == null ? null : limit(exception.getClass().getName(), 160));
            auditService.record(event);
        } catch (RuntimeException auditFailure) {
            // An audit storage failure must not replace the payment-provider response.
            org.slf4j.LoggerFactory.getLogger(StalePaymentCreationReviewController.class)
                    .error("Failed to persist provider-status lookup audit event", auditFailure);
        }
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}

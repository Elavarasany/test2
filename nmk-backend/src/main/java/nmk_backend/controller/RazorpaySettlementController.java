package nmk_backend.controller;

import nmk_backend.service.RazorpaySettlementGatewayException;
import nmk_backend.service.RazorpaySettlementPersistenceService;
import nmk_backend.service.RazorpaySettlementSyncService;
import nmk_backend.exception.PaymentConfigurationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/payments/settlements")
public class RazorpaySettlementController {
    private final RazorpaySettlementSyncService syncService;
    private final RazorpaySettlementPersistenceService persistenceService;

    public RazorpaySettlementController(
            RazorpaySettlementSyncService syncService,
            RazorpaySettlementPersistenceService persistenceService
    ) {
        this.syncService = syncService;
        this.persistenceService = persistenceService;
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) Integer day
    ) {
        try {
            return ResponseEntity.ok(syncService.sync(year, month, day));
        } catch (PaymentConfigurationException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Razorpay API credentials are not configured."));
        } catch (RazorpaySettlementGatewayException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "Razorpay settlement API request failed."));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        try {
            return ResponseEntity.ok(persistenceService.list(page, size));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/exceptions")
    public ResponseEntity<?> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        try {
            return ResponseEntity.ok(persistenceService.exceptions(status, page, size));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/{settlementId}/entries")
    public ResponseEntity<?> entries(
            @PathVariable Long settlementId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        try {
            return ResponseEntity.ok(persistenceService.entries(settlementId, page, size));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}

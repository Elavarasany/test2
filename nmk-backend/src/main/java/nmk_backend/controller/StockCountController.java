package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.StockCountLineRequest;
import nmk_backend.dto.StockCountStartRequest;
import nmk_backend.service.StockCountService;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory/stock-counts")
public class StockCountController {

    private final StockCountService stockCountService;
    private final AdminActionAuthorizationService authorizationService;

    public StockCountController(StockCountService stockCountService, AdminActionAuthorizationService authorizationService) {
        this.stockCountService = stockCountService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(@RequestParam Long locationId) {
        try {
            return ResponseEntity.ok(stockCountService.getCurrent(locationId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable Long sessionId) {
        try {
            return ResponseEntity.ok(stockCountService.getSession(sessionId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(
            @Valid @RequestBody StockCountStartRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    stockCountService.start(request, authentication.getName())
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PatchMapping("/{sessionId}/lines/{lineId}")
    public ResponseEntity<?> recordCount(
            @PathVariable Long sessionId,
            @PathVariable Long lineId,
            @Valid @RequestBody StockCountLineRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(stockCountService.recordCount(
                    sessionId, lineId, request, authentication.getName()
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/submit")
    public ResponseEntity<?> submit(
            @PathVariable Long sessionId,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(stockCountService.submit(sessionId, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/post")
    public ResponseEntity<?> post(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "STOCK_COUNT_POST");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.ok(stockCountService.post(sessionId, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "STOCK_COUNT_CANCEL");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.ok(stockCountService.cancel(sessionId, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
package nmk_backend.controller;

import nmk_backend.dto.PosCheckoutRequest;
import nmk_backend.dto.PosSaleResponse;
import nmk_backend.dto.PosCatalogResponse;
import nmk_backend.dto.PosLocationResponse;
import nmk_backend.service.PosCatalogService;
import nmk_backend.service.PosCheckoutService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos")
public class PosController {

    private final PosCheckoutService checkoutService;
    private final PosCatalogService catalogService;

    public PosController(
            PosCheckoutService checkoutService,
            PosCatalogService catalogService
    ) {
        this.checkoutService = checkoutService;
        this.catalogService = catalogService;
    }

    @GetMapping("/locations")
    public ResponseEntity<?> getLocations(Authentication authentication) {
        return ResponseEntity.ok(catalogService.getActiveLocations(authentication.getName(), authenticatedRole(authentication)));
    }

    @GetMapping("/catalog")
    public ResponseEntity<?> getCatalog(@RequestParam Long locationId, Authentication authentication) {
        if (locationId == null || locationId <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "A positive locationId is required"
            ));
        }
        try {
            return ResponseEntity.ok(catalogService.getCatalog(locationId, authentication.getName(), authenticatedRole(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/sales")
    public ResponseEntity<?> getSales(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long locationId,
            Authentication authentication
    ) {
        if (page < 0 || size < 1 || size > 100
                || (locationId != null && locationId <= 0)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Page must be non-negative, size must be 1-100, and locationId must be positive"
            ));
        }
        PageRequest pageable = PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );
        Page<PosSaleResponse> result = checkoutService.getSales(locationId, pageable, authentication.getName(), authenticatedRole(authentication));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sales/{saleId}")
    public ResponseEntity<?> getSale(@PathVariable Long saleId, Authentication authentication) {
        if (saleId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(checkoutService.getSale(saleId, authentication.getName(), authenticatedRole(authentication)));
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(
            @Valid @RequestBody PosCheckoutRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Staff authentication is required"));
        }

        try {
            PosSaleResponse response = checkoutService.checkout(
                    request,
                    authentication.getName(),
                    authenticatedRole(authentication)
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (DataIntegrityViolationException exception) {
            // Covers simultaneous requests racing on the unique sale/stock idempotency keys.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message",
                    "A checkout with this idempotency key is already being processed or was already recorded. Retry using the same key to retrieve the completed sale."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message",
                    exception.getMessage() == null ? "Invalid checkout request" : exception.getMessage()
            ));
        }
    }

    private String authenticatedRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .findFirst()
                .orElse("CASHIER");
    }
}

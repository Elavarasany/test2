package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.InventoryBalanceResponse;
import nmk_backend.dto.InventoryAdjustmentRequest;
import nmk_backend.dto.InventoryReceiptRequest;
import nmk_backend.dto.InventoryReorderLevelRequest;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import nmk_backend.service.InventoryService;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final AdminRepository adminRepository;
    private final AdminActionAuthorizationService authorizationService;

    public InventoryController(
            InventoryService inventoryService,
            AdminRepository adminRepository,
            AdminActionAuthorizationService authorizationService
    ) {
        this.inventoryService = inventoryService;
        this.adminRepository = adminRepository;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/low-stock")
    public ResponseEntity<?> getLowStock(@RequestParam Long locationId) {
        try {
            return ResponseEntity.ok(inventoryService.getLowStockBalances(locationId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/movements")
    public ResponseEntity<?> getMovementHistory(
            @RequestParam Long locationId,
            @RequestParam(required = false) Long variantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        try {
            return ResponseEntity.ok(
                    inventoryService.getMovementHistory(locationId, variantId, page, size)
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null
                            ? "Invalid inventory movement history request"
                            : exception.getMessage()
            ));
        }
    }

    @PatchMapping("/reorder-level")
    public ResponseEntity<?> updateReorderLevel(
            @Valid @RequestBody InventoryReorderLevelRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Admin authentication is required"));
        }
        try {
            var balance = inventoryService.updateReorderLevel(
                    request.locationId(), request.variantId(),
                    request.reorderLevel(), authentication.getName()
            );
            return ResponseEntity.ok(InventoryBalanceResponse.from(balance));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid reorder level" : exception.getMessage()
            ));
        }
    }

    @PostMapping("/adjust")
    public ResponseEntity<?> adjustStock(
            @Valid @RequestBody InventoryAdjustmentRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("message", "Admin authentication is required")
            );
        }

        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "INVENTORY_ADJUSTMENT");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }

        Admin admin = adminRepository.findByUsername(authentication.getName())
                .orElse(null);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("message", "Authenticated admin account was not found")
            );
        }

        try {
            var balance = inventoryService.adjustStockByAdmin(
                    request.getLocationId(),
                    request.getVariantId(),
                    request.getQuantityDelta(),
                    request.getIdempotencyKey(),
                    admin.getId(),
                    request.getReason()
            );
            return ResponseEntity.ok(InventoryBalanceResponse.from(balance));
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message",
                    "A stock adjustment with this idempotency key conflicts with an existing operation. Verify the request before retrying."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message",
                    exception.getMessage() == null ? "Invalid stock adjustment" : exception.getMessage()
            ));
        }
    }

    @PostMapping("/receive")
    public ResponseEntity<?> receiveStock(
            @Valid @RequestBody InventoryReceiptRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("message", "Admin authentication is required")
            );
        }

        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "INVENTORY_RECEIPT");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }

        Admin admin = adminRepository.findByUsername(authentication.getName())
                .orElse(null);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("message", "Authenticated admin account was not found")
            );
        }

        try {
            var balance = inventoryService.receiveStockByAdmin(
                    request.getLocationId(),
                    request.getVariantId(),
                    request.getQuantity(),
                    request.getIdempotencyKey(),
                    admin.getId(),
                    request.getReason().trim()
            );

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(InventoryBalanceResponse.from(balance));

        } catch (DataIntegrityViolationException exception) {
            // A concurrent request may win the globally unique movement-key insert.
            // The service transaction is rolled back before control reaches this catch.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message",
                    "An inventory operation with this idempotency key conflicts with an existing operation. Verify the key and request details before retrying."
            ));
        }
    }
}

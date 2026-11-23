package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.PosReturnRequest;
import nmk_backend.dto.PosVoidRequest;
import nmk_backend.service.PosReturnService;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/sales/{saleId}")
public class PosReturnController {

    private final PosReturnService returnService;
    private final AdminActionAuthorizationService authorizationService;

    public PosReturnController(PosReturnService returnService, AdminActionAuthorizationService authorizationService) {
        this.returnService = returnService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/returns")
    public ResponseEntity<?> getReturns(@PathVariable Long saleId, Authentication authentication) {
        try {
            return ResponseEntity.ok(returnService.getReturns(saleId, authentication.getName(), authenticatedRole(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/returns")
    public ResponseEntity<?> processReturn(
            @PathVariable Long saleId,
            @Valid @RequestBody PosReturnRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Manager authentication is required"));
        }
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "POS_RETURN");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    returnService.processReturn(saleId, request, authentication.getName(), authenticatedRole(authentication))
            );
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "A return with this idempotency key or return number already exists. Retry the same request with the same key."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid return request" : exception.getMessage()
            ));
        }
    }

    @PostMapping("/void")
    public ResponseEntity<?> voidSale(
            @PathVariable Long saleId,
            @Valid @RequestBody PosVoidRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Manager authentication is required"));
        }
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "POS_VOID");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    returnService.processVoid(saleId, request, authentication.getName(), authenticatedRole(authentication))
            );
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "A void with this idempotency key or return number already exists. Retry the same request with the same key."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid void request" : exception.getMessage()
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
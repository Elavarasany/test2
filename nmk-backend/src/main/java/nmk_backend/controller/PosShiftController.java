package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.*;
import nmk_backend.service.PosShiftService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos")
public class PosShiftController {

    private final PosShiftService shiftService;

    public PosShiftController(PosShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @GetMapping("/registers")
    public ResponseEntity<?> getRegisters(@RequestParam Long locationId, Authentication authentication) {
        try {
            return ResponseEntity.ok(shiftService.getRegisters(locationId, authentication.getName(), role(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/registers")
    public ResponseEntity<?> createRegister(@Valid @RequestBody PosRegisterCreateRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(shiftService.createRegister(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/shifts/history")
    public ResponseEntity<?> getShiftHistory(
            @RequestParam Long locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(shiftService.getShiftHistory(locationId, page, size, authentication.getName(), role(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/shifts/current")
    public ResponseEntity<?> getCurrentShift(
            @RequestParam Long registerId,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(shiftService.getCurrentShift(
                    registerId, authentication.getName(), role(authentication)
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/shifts/open")
    public ResponseEntity<?> openShift(
            @Valid @RequestBody PosShiftOpenRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    shiftService.openShift(request, authentication.getName(), role(authentication))
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/shifts/{shiftId}/cash-movements")
    public ResponseEntity<?> recordCashMovement(
            @PathVariable Long shiftId,
            @Valid @RequestBody PosCashMovementRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(shiftService.recordCashMovement(
                    shiftId, request, authentication.getName(), role(authentication)
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/shifts/{shiftId}/cash-movements")
    public ResponseEntity<?> getCashMovements(
            @PathVariable Long shiftId,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(shiftService.getCashMovements(
                    shiftId, authentication.getName(), role(authentication)
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/shifts/{shiftId}/close")
    public ResponseEntity<?> closeShift(
            @PathVariable Long shiftId,
            @Valid @RequestBody PosShiftCloseRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(shiftService.closeShift(
                    shiftId, request, authentication.getName(), role(authentication)
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    private String role(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .findFirst().orElse("CASHIER");
    }
}
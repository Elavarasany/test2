package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.StaffAccountCreateRequest;
import nmk_backend.dto.StaffAccountRoleRequest;
import nmk_backend.dto.StaffAccountStatusRequest;
import nmk_backend.service.StaffAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/staff")
public class StaffAccountController {

    private final StaffAccountService staffAccountService;

    public StaffAccountController(StaffAccountService staffAccountService) {
        this.staffAccountService = staffAccountService;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(staffAccountService.list());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody StaffAccountCreateRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(staffAccountService.create(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> setStatus(
            @PathVariable Long id,
            @Valid @RequestBody StaffAccountStatusRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(staffAccountService.setEnabled(
                    id, request.enabled(), authentication.getName()
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<?> setRole(
            @PathVariable Long id,
            @Valid @RequestBody StaffAccountRoleRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(staffAccountService.setRole(
                    id, request.role(), authentication.getName()
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
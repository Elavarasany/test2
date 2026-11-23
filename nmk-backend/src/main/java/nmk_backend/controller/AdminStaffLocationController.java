package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.AdminLocationAssignmentRequest;
import nmk_backend.service.AdminLocationAccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/staff")
public class AdminStaffLocationController {
    private final AdminLocationAccessService service;

    public AdminStaffLocationController(AdminLocationAccessService service) {
        this.service = service;
    }

    @GetMapping("/{staffId}/locations")
    public ResponseEntity<?> getAssignments(@PathVariable Long staffId) {
        try {
            if (staffId == null || staffId <= 0) throw new IllegalArgumentException("A valid staff ID is required");
            return ResponseEntity.ok(Map.of("staffId", staffId, "locationIds", service.getAssignedLocationIds(staffId)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PutMapping("/{staffId}/locations")
    public ResponseEntity<?> replaceAssignments(@PathVariable Long staffId,
                                                  @Valid @RequestBody AdminLocationAssignmentRequest request) {
        try {
            if (staffId == null || staffId <= 0) throw new IllegalArgumentException("A valid staff ID is required");
            return ResponseEntity.ok(Map.of("staffId", staffId,
                    "locationIds", service.replaceAssignments(staffId, request.locationIds())));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
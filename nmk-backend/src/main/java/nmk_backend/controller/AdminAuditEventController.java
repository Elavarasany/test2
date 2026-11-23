package nmk_backend.controller;

import nmk_backend.service.AdminAuditEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/audit/events")
public class AdminAuditEventController {
    private final AdminAuditEventService service;

    public AdminAuditEventController(AdminAuditEventService service) {
        this.service = service;
    }

    @GetMapping("/integrity")
    public ResponseEntity<?> verifyIntegrity() {
        return ResponseEntity.ok(service.verifyIntegrity());
    }

    @GetMapping
    public ResponseEntity<?> getEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        try {
            return ResponseEntity.ok(service.getEvents(page, size));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
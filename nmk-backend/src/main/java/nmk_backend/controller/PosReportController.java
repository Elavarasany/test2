package nmk_backend.controller;

import nmk_backend.service.PosReportService;
import nmk_backend.service.PosShiftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/reports")
public class PosReportController {

    private final PosReportService reportService;
    private final PosShiftService shiftService;

    public PosReportController(PosReportService reportService, PosShiftService shiftService) {
        this.reportService = reportService;
        this.shiftService = shiftService;
    }

    @GetMapping("/inventory")
    public ResponseEntity<?> getInventoryReport(@RequestParam Long locationId, Authentication authentication) {
        try {
            return ResponseEntity.ok(reportService.getInventoryReport(locationId, authentication.getName(), role(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid inventory report request" : exception.getMessage()
            ));
        }
    }

    @GetMapping("/inventory/entries")
    public ResponseEntity<?> getInventoryReportEntries(
            @RequestParam Long locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(reportService.getInventoryReportEntries(
                    locationId, page, size, authentication.getName(), role(authentication)
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid inventory report request" : exception.getMessage()
            ));
        }
    }

    @GetMapping("/cash-reconciliation")
    public ResponseEntity<?> getDailyCashReconciliation(
            @RequestParam Long locationId,
            @RequestParam LocalDate date,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(shiftService.getDailyCashReconciliation(
                    locationId, date, authentication.getName(), role(authentication)
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null
                            ? "Invalid cash reconciliation request" : exception.getMessage()
            ));
        }
    }

    @GetMapping("/sales")
    public ResponseEntity<?> getSalesReport(
            @RequestParam Long locationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(reportService.getSalesReport(locationId, from, to, authentication.getName(), role(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid report request" : exception.getMessage()
            ));
        }
    }
    private String role(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("ROLE_")).map(value -> value.substring(5))
                .findFirst().orElse("CASHIER");
    }
}
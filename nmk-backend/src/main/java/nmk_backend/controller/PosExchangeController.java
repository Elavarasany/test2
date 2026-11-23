package nmk_backend.controller;
import jakarta.validation.Valid;
import nmk_backend.dto.PosExchangeRequest;
import nmk_backend.service.PosExchangeService;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin/pos/sales/{saleId}/exchange")
public class PosExchangeController {
 private final PosExchangeService service;
 private final AdminActionAuthorizationService authorizationService;
 public PosExchangeController(PosExchangeService service,AdminActionAuthorizationService authorizationService) { this.service=service; this.authorizationService=authorizationService; }
 @GetMapping
 public ResponseEntity<?> getExchanges(@PathVariable Long saleId,Authentication auth) {
  try { return ResponseEntity.ok(service.getExchanges(saleId,auth.getName(),auth.getAuthorities().stream().map(a->a.getAuthority()).filter(a->a.startsWith("ROLE_")).map(a->a.substring(5)).findFirst().orElse("CASHIER"))); }
  catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("message",ex.getMessage())); }
 }
 @PostMapping
 public ResponseEntity<?> exchange(@PathVariable Long saleId,@Valid @RequestBody PosExchangeRequest request,
  @RequestHeader(value="X-Admin-Action-Authorization",required=false) String authorizationToken,Authentication auth) {
  if(auth==null||auth.getName()==null||auth.getName().isBlank())
   return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message","POS staff authentication is required"));
  String role=auth.getAuthorities().stream().map(a->a.getAuthority()).filter(a->a.startsWith("ROLE_"))
   .map(a->a.substring(5)).findFirst().orElse("CASHIER");
  try { authorizationService.consumeAuthorization(auth.getName(),authorizationToken,"POS_EXCHANGE"); }
  catch(IllegalArgumentException ex) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message",ex.getMessage())); }
  try { return ResponseEntity.status(HttpStatus.CREATED).body(service.exchange(saleId,request,auth.getName(),role)); }
  catch(DataIntegrityViolationException ex) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","Exchange key already exists; retry the identical request.")); }
  catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("message",ex.getMessage()==null?"Invalid exchange request":ex.getMessage())); }
 }
}
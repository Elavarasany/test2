package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.ProductVariantRequest;
import nmk_backend.dto.ProductVariantResponse;
import nmk_backend.service.ProductVariantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/products/{productId}/variants")
public class AdminProductVariantController {

    private final ProductVariantService variantService;

    public AdminProductVariantController(ProductVariantService variantService) {
        this.variantService = variantService;
    }

    @GetMapping
    public ResponseEntity<List<ProductVariantResponse>> getVariants(
            @PathVariable Long productId
    ) {
        List<ProductVariantResponse> variants = variantService.getVariants(productId)
                .stream()
                .map(ProductVariantResponse::from)
                .toList();

        return ResponseEntity.ok(variants);
    }

    @PostMapping
    public ResponseEntity<?> createVariant(
            @PathVariable Long productId,
            @Valid @RequestBody ProductVariantRequest request
    ) {
        try {
            ProductVariantResponse response = ProductVariantResponse.from(
                    variantService.createVariant(productId, request)
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (ResponseStatusException exception) {
            return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                    "message",
                    exception.getReason() == null ? "Invalid variant request" : exception.getReason()
            ));
        }
    }
}

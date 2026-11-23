package nmk_backend.controller;

import nmk_backend.dto.CustomerProductCatalogResponse;
import nmk_backend.entity.Product;
import nmk_backend.service.CustomerProductCatalogService;
import nmk_backend.service.ProductService;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;


@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final CustomerProductCatalogService customerProductCatalogService;

    public ProductController(
            ProductService productService,
            CustomerProductCatalogService customerProductCatalogService
    ) {
        this.productService = productService;
        this.customerProductCatalogService = customerProductCatalogService;
    }

    @GetMapping
    public ResponseEntity<?> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "Page must be non-negative and size must be between 1 and 100"
            ));
        }
        return ResponseEntity.ok(customerProductCatalogService.getActiveProducts(PageRequest.of(
                page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))
        )));
    }

    @PostMapping
    public Product addProduct(@RequestBody Product product) {
        return productService.addProduct(product);
    }

    @PutMapping("/{id}")
    public Product updateProduct(
            @PathVariable Long id,
            @RequestBody Product product) {
        return productService.updateProduct(id, product);
    }

    @GetMapping("/{id}")
    public CustomerProductCatalogResponse.ProductItem getProductById(@PathVariable Long id) {
        return customerProductCatalogService.getActiveProductById(id);
    }
}

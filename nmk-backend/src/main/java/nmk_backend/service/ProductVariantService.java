package nmk_backend.service;

import nmk_backend.dto.ProductVariantRequest;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class ProductVariantService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    public ProductVariantService(
            ProductRepository productRepository,
            ProductVariantRepository variantRepository
    ) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductVariant> getVariants(Long productId) {
        Product product = getActiveProduct(productId);
        return variantRepository.findByProductId(product.getId()).stream()
                .sorted((left, right) -> left.getSku().compareToIgnoreCase(right.getSku()))
                .toList();
    }

    @Transactional
    public ProductVariant createVariant(Long productId, ProductVariantRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variant details are required");
        }

        Product product = getActiveProductForUpdate(productId);

        String sku = normalizeRequired(request.sku(), "SKU")
                .toUpperCase(Locale.ROOT);
        String barcode = normalizeOptional(request.barcode());
        String variantName = normalizeRequired(request.variantName(), "Variant name");

        if (variantRepository.existsBySku(sku)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already exists");
        }

        if (barcode != null && variantRepository.existsByBarcode(barcode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Barcode already exists");
        }

        if (request.sellingPriceOverride() != null
                && request.sellingPriceOverride().signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Selling price override must be greater than zero"
            );
        }

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setBarcode(barcode);
        variant.setVariantName(variantName);
        variant.setAttributes(request.attributes());
        variant.setSellingPriceOverride(request.sellingPriceOverride());
        variant.setActive(true);

        try {
            return variantRepository.saveAndFlush(variant);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            // A concurrent request may win the unique SKU/barcode constraint.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "SKU or barcode already exists",
                    exception
            );
        }
    }

    private Product getActiveProduct(Long productId) {
        return findActiveProduct(productId, false);
    }

    private Product getActiveProductForUpdate(Long productId) {
        return findActiveProduct(productId, true);
    }

    private Product findActiveProduct(Long productId, boolean lock) {
        if (productId == null || productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid product ID is required");
        }

        Product product = (lock
                ? productRepository.findByIdForUpdate(productId)
                : productRepository.findById(productId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found"
                ));

        if (!product.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot manage variants for an inactive product"
            );
        }

        return product;
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " is required"
            );
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

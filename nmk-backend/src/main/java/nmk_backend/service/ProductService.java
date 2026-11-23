
package nmk_backend.service;

import nmk_backend.entity.Product;
import nmk_backend.dto.AdminProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import nmk_backend.repository.ProductRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Product> getActiveProducts() {
        return productRepository.findAll()
                .stream()
                .filter(Product::isActive)
                .toList();
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Page<AdminProductResponse> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(AdminProductResponse::from);
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Product not found with ID: " + id));
    }

    @Transactional
    public Product addProduct(Product product) {

        validateProduct(product);

        product.setId(null);
        product.setVersion(null);

        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(Long id, Product updatedProduct) {

        Product existingProduct = getProductById(id);

        if (updatedProduct.getVersion() == null ||
                !Objects.equals(updatedProduct.getVersion(), existingProduct.getVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This product was changed by another operation. Reload the product and try again."
            );
        }

        validateProduct(updatedProduct);

        existingProduct.setName(updatedProduct.getName());
        existingProduct.setGender(updatedProduct.getGender());
        existingProduct.setCategory(updatedProduct.getCategory());
        existingProduct.setDescription(updatedProduct.getDescription());
        existingProduct.setPrice(updatedProduct.getPrice());
        existingProduct.setStockQuantity(updatedProduct.getStockQuantity());
        existingProduct.setImageUrl(updatedProduct.getImageUrl());
        existingProduct.setSpecifications(updatedProduct.getSpecifications());
        existingProduct.setActive(updatedProduct.isActive());

        return productRepository.saveAndFlush(existingProduct);
    }

    private void validateProduct(Product product) {

        if (product.getName() == null || product.getName().isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }

        if (product.getPrice() == null || product.getPrice().signum() < 0) {
            throw new IllegalArgumentException("Valid product price is required");
        }

        if (product.getStockQuantity() == null || product.getStockQuantity() < 0) {
            throw new IllegalArgumentException("Valid stock quantity is required");
        }
    }

    @Transactional
    public void deleteProduct(Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid product ID is required");
        }

        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found"
                ));

        // Products are referenced by variants, inventory balances, stock movements,
        // and historical sales. Preserve those records and deactivate the catalogue item.
        if (product.isActive()) {
            product.setActive(false);
            productRepository.saveAndFlush(product);
        }
    }
}

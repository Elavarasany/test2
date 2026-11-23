package nmk_backend.repository;

import nmk_backend.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setName("Stock Test Product");
        product.setPrice(new BigDecimal("999.00"));
        product.setStockQuantity(10);
        product.setActive(true);

        product = productRepository.saveAndFlush(product);
        entityManager.clear();
    }

    @Test
    void reserveStockDecreasesQuantityAndIncrementsVersion() {
        Product saved = productRepository.findById(product.getId()).orElseThrow();
        Long originalVersion = saved.getVersion();

        int updatedRows = productRepository.reserveStock(product.getId(), 3);

        entityManager.clear();
        Product result = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(1, updatedRows);
        assertEquals(7, result.getStockQuantity());
        assertEquals(originalVersion + 1, result.getVersion());
    }

    @Test
    void releaseStockIncreasesQuantityAndIncrementsVersion() {
        Product saved = productRepository.findById(product.getId()).orElseThrow();
        Long originalVersion = saved.getVersion();

        int updatedRows = productRepository.releaseStock(product.getId(), 3);

        entityManager.clear();
        Product result = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(1, updatedRows);
        assertEquals(13, result.getStockQuantity());
        assertEquals(originalVersion + 1, result.getVersion());
    }

    @Test
    void reserveStockFailsWhenQuantityExceedsAvailableStock() {
        Product saved = productRepository.findById(product.getId()).orElseThrow();
        Long originalVersion = saved.getVersion();

        int updatedRows = productRepository.reserveStock(product.getId(), 11);

        entityManager.clear();
        Product result = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(0, updatedRows);
        assertEquals(10, result.getStockQuantity());
        assertEquals(originalVersion, result.getVersion());
    }

    @Test
    void reserveStockFailsForInactiveProduct() {
        Product inactive = productRepository.findById(product.getId()).orElseThrow();
        inactive.setActive(false);
        productRepository.saveAndFlush(inactive);
        entityManager.clear();

        int updatedRows = productRepository.reserveStock(product.getId(), 1);

        entityManager.clear();
        Product result = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(0, updatedRows);
        assertEquals(10, result.getStockQuantity());
    }
}

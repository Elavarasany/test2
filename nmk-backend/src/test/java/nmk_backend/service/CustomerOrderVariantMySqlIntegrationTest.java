package nmk_backend.service;

import jakarta.persistence.EntityManager;
import nmk_backend.dto.CreateOrderRequest;
import nmk_backend.dto.OrderItemRequest;
import nmk_backend.entity.InventoryBalance;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.repository.InventoryBalanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
@TestPropertySource(properties = {
        "inventory.checkout.location-code=MYSQL-ORDER-TEST"
})
@Transactional
class CustomerOrderVariantMySqlIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Test
    void customerCheckoutReservesStockForSelectedVariant() {

        // Create an isolated checkout inventory location.
        InventoryLocation location = new InventoryLocation();
        location.setCode("MYSQL-ORDER-TEST");
        location.setName("MySQL Order Integration Test");
        location.setActive(true);

        entityManager.persist(location);
        entityManager.flush();

        // Create an active product.
        Product product = new Product();
        product.setName("Integration Test Eyeglasses");
        product.setCategory("Eyeglasses");
        product.setGender("Unisex");
        product.setDescription("Temporary integration test product");
        product.setPrice(new BigDecimal("999.00"));
        product.setStockQuantity(0);
        product.setActive(true);

        entityManager.persist(product);
        entityManager.flush();

        // Create the selected variant with its own selling price.
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("MYSQL-ORDER-VARIANT-001");
        variant.setVariantName("Black Full Rim - Medium");
        variant.setAttributes(Map.of(
                "color", "Black",
                "size", "Medium",
                "frameType", "Full Rim"
        ));
        variant.setSellingPriceOverride(new BigDecimal("1099.00"));
        variant.setActive(true);

        entityManager.persist(variant);
        entityManager.flush();

        // Receive five units for this exact variant.
        inventoryService.receiveStock(
                location.getId(),
                variant.getId(),
                5,
                "TEST-RECEIPT-CHECKOUT-VARIANT-001",
                null,
                "Integration test stock receipt"
        );

        // Build the customer checkout request.
        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(product.getId());
        itemRequest.setVariantId(variant.getId());
        itemRequest.setQuantity(2);
        itemRequest.setPrescriptionType("Frame Only");
        itemRequest.setPhotochromic(false);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerName("Integration Test Customer");
        request.setEmail("checkout-test@example.com");
        request.setPhone("9000000000");
        request.setAddress("Test Address");
        request.setCity("Avalurpet");
        request.setState("Tamil Nadu");
        request.setPincode("604201");
        request.setItems(List.of(itemRequest));

        // Execute the actual checkout service.
        var order = orderService.createPendingOrder(request);

        // Verify the order contains the selected variant snapshot.
        assertNotNull(order.getId());
        assertEquals(new BigDecimal("2198.00"), order.getTotalAmount());
        assertEquals(1, order.getItems().size());

        var savedItem = order.getItems().get(0);

        assertEquals(product.getId(), savedItem.getProductId());
        assertEquals(variant.getId(), savedItem.getVariantId());
        assertEquals("MYSQL-ORDER-VARIANT-001", savedItem.getVariantSku());
        assertEquals("Black Full Rim - Medium", savedItem.getVariantName());
        assertEquals(new BigDecimal("1099.00"), savedItem.getPrice());

        // Verify stock was reserved against the selected variant.
        InventoryBalance balance = balanceRepository
                .findByLocationIdAndVariantId(
                        location.getId(),
                        variant.getId()
                )
                .orElseThrow();

        assertEquals(5, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());
        assertEquals(3, balance.getAvailableQuantity());
    }
}

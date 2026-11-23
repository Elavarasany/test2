package nmk_backend.security;

import nmk_backend.entity.Admin;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.entity.Order;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.entity.InventoryBalance;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.AdminAuditEventRepository;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import nmk_backend.repository.InventoryBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = "inventory.checkout.location-code=AVP-01")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosAuthorizationIntegrationTest {

    private static final String POS_LOCATIONS = "/api/admin/pos/locations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private AdminAuditEventRepository auditEventRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InventoryLocationRepository locationRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private InventoryBalanceRepository inventoryBalanceRepository;

    private String adminToken;
    private InventoryLocation testLocation;

    @BeforeEach
    void setUp() {
        InventoryLocation location = new InventoryLocation();
        location.setCode("POS-AUTH-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        location.setName("POS Authorization Test Location");
        location.setActive(true);
        testLocation = locationRepository.saveAndFlush(location);
        adminToken = tokenForRole("ADMIN");
    }

    private String tokenForRole(String role) {
        String username = "pos-security-" + role.toLowerCase() + "-" + java.util.UUID.randomUUID();
        Admin account = new Admin();
        account.setUsername(username);
        account.setPassword(passwordEncoder.encode("test-only-password"));
        account.setRole(role);
        account.setEnabled(true);
        if (!"ADMIN".equals(role)) account.setAssignedLocations(java.util.Set.of(testLocation));
        adminRepository.saveAndFlush(account);
        return jwtService.generateToken(username);
    }

    @Test
    void anonymousRequestCannotAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)).andExpect(status().isForbidden());
    }

    @Test
    void publicProductCatalogueReturnsPagedProductsWithVariantStock() throws Exception {
        InventoryLocation location = new InventoryLocation();
        location.setCode("AVP-01");
        location.setName("Public Catalogue Test Store");
        location.setActive(true);
        location = locationRepository.saveAndFlush(location);

        Product product = new Product();
        product.setName("Public Paged Frame");
        product.setCategory("Eyeglasses");
        product.setGender("Unisex");
        product.setPrice(new java.math.BigDecimal("100.00"));
        product.setStockQuantity(0);
        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("PUBLIC-PAGE-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        variant.setVariantName("Black");
        variant.setAttributes(java.util.Map.of("color", "Black"));
        variant.setActive(true);
        variant = productVariantRepository.saveAndFlush(variant);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(7);
        balance.setReservedQuantity(2);
        inventoryBalanceRepository.saveAndFlush(balance);

        mockMvc.perform(get("/api/products?page=0&size=24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(product.getId()))
                .andExpect(jsonPath("$.content[0].variants[0].id").value(variant.getId()))
                .andExpect(jsonPath("$.content[0].variants[0].availableQuantity").value(5));
    }

    @Test
    void adminProductListReturnsBoundedPages() throws Exception {
        long before = productRepository.count();
        java.util.List<Product> newProducts = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Product product = new Product();
            product.setName("Product pagination " + i);
            product.setCategory("Eyeglasses");
            product.setGender("Unisex");
            product.setPrice(new java.math.BigDecimal("100.00"));
            product.setStockQuantity(10);
            newProducts.add(product);
        }
        productRepository.saveAllAndFlush(newProducts);

        mockMvc.perform(get("/api/admin/products?page=0&size=10")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(before + 30))
                .andExpect(jsonPath("$.content[0].version").exists());
    }

    @Test
    void adminCustomerListReturnsDatabaseAggregatedPages() throws Exception {
        java.util.List<Order> newOrders = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Order order = new Order();
            order.setCustomerName("Customer pagination " + i);
            order.setEmail("pagination" + i + "@example.test");
            order.setPhone("987650" + String.format("%04d", i));
            order.setTotalAmount(new java.math.BigDecimal("100.00"));
            order.setPaymentStatus("PAID");
            newOrders.add(order);
        }
        orderRepository.saveAllAndFlush(newOrders);

        mockMvc.perform(get("/api/admin/orders/customers?page=0&size=10")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$.content[0].totalOrders").value(1));
    }

    @Test
    void adminOrderListReturnsBoundedPageMetadata() throws Exception {
        long before = orderRepository.count();
        java.util.List<Order> newOrders = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Order order = new Order();
            order.setCustomerName("Order pagination " + i);
            order.setTotalAmount(new java.math.BigDecimal("100.00"));
            order.setPaymentStatus("PENDING");
            newOrders.add(order);
        }
        orderRepository.saveAllAndFlush(newOrders);

        mockMvc.perform(get("/api/admin/orders?page=0&size=10")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(before + 30));
    }

    @Test
    void securityFilterRejectionIsWrittenToAuditLog() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        long before = auditEventRepository.count();

        mockMvc.perform(post("/api/admin/staff/999/locations")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[1]}"))
                .andExpect(status().isForbidden());

        var events = auditEventRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 20,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id"))
        );
        assertEquals(before + 1, auditEventRepository.count());
        assertTrue(events.getContent().stream().anyMatch(event ->
                "/api/admin/staff/999/locations".equals(event.getRequestPath())
                        && "SECURITY_ACCESS_DENIED".equals(event.getErrorType())));
    }

    @Test
    void stalePaymentProviderReviewIsRestrictedToManagersAndAdmins() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        String cashierToken = tokenForRole("CASHIER");
        String reviewUrl = "/api/admin/pos/payments/stale-creation-review/999999999/provider-status";

        mockMvc.perform(get(reviewUrl))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(reviewUrl)
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        // A missing local order returns the controller's validation response,
        // proving authorized roles passed the security layer without contacting Razorpay.
        mockMvc.perform(get(reviewUrl)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(reviewUrl)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidBearerTokenCannotAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validAdminJwtCanAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCanAccessPosLocationPicker() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk());
    }


    @Test
    void cashierCannotReadCatalogForAnUnassignedLocation() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        InventoryLocation otherLocation = new InventoryLocation();
        otherLocation.setCode("POS-OTHER-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        otherLocation.setName("Unassigned test location");
        otherLocation.setActive(true);
        otherLocation = locationRepository.saveAndFlush(otherLocation);

        mockMvc.perform(get("/api/admin/pos/catalog")
                        .param("locationId", otherLocation.getId().toString())
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotApplySaleDiscountThroughPosCheckoutApi() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        String body = """
                {
                  "locationId": 1,
                  "shiftId": 1,
                  "discountAmount": 10.00,
                  "idempotencyKey": "cashier-discount-security-test",
                  "items": [{"productId": 1, "variantId": 1, "quantity": 1}],
                  "payments": [{"paymentMethod": "CASH", "amount": 90.00, "tenderedAmount": 90.00}]
                }
                """;

        mockMvc.perform(post("/api/admin/pos/checkout")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierCannotReadManagerShiftHistory() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/pos/shifts/history?locationId=" + testLocation.getId())
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadShiftHistory() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/pos/shifts/history?locationId=" + testLocation.getId())
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotBrowseAllSalesHistory() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/pos/sales")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanAccessSalesHistory() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/pos/sales")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotProcessCustomerReturns() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/returns")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReachReturnRequestValidation() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/returns")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void managerCannotProcessReturnWithoutFreshPasswordAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/returns")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"shiftId":1,"reason":"test return","idempotencyKey":"test-return-auth",
                   "items":[{"saleItemId":1,"quantity":1,"restockDisposition":"RESTOCK"}],"refundPayments":[]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotVoidWithoutFreshPasswordAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"shiftId":1,"reason":"test void","idempotencyKey":"test-void-auth","refundPayments":[]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotAdjustInventoryWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/inventory/adjust")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"variantId":1,"quantityDelta":1,"idempotencyKey":"test-adjust-auth","reason":"test"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotReceiveInventoryWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/inventory/receive")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"variantId":1,"quantity":1,"idempotencyKey":"test-receive-auth","reason":"test"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotPostOrCancelStockCountWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/inventory/stock-counts/1/post")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/inventory/stock-counts/1/cancel")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotReceivePurchaseOrderGoodsWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/procurement/purchase-orders/1/receive")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"reason":"test receipt","idempotencyKey":"test-goods-receipt-auth",
                   "items":[{"purchaseOrderItemId":1,"quantity":1}]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotReturnGoodsToSupplierWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/procurement/purchase-orders/1/supplier-returns")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"reason":"test supplier return","idempotencyKey":"test-supplier-return-auth",
                   "items":[{"purchaseOrderItemId":1,"quantity":1}]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotSubmitPurchaseOrderWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/procurement/purchase-orders/1/submit")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotProcessExchanges() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/exchange")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerMustProvideFreshAuthorizationToProcessExchange() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/exchange")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"shiftId":1,"reason":"test exchange","idempotencyKey":"test-exchange-auth",
                   "returnItems":[{"saleItemId":1,"quantity":1,"restockDisposition":"RESTOCK"}],
                   "replacementItems":[{"productId":1,"variantId":1,"quantity":1}],
                   "replacementDiscountAmount":0.00,"collectionPayments":[],"refundPayments":[]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotVoidCompletedSale() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReachSaleVoidRequestValidation() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierCannotVoidCompletedSales() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReachFullVoidRequestValidation() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void cashierCannotReadStockMovementHistory() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/inventory/movements?locationId=1")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadStockMovementHistory() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        InventoryLocation location = new InventoryLocation();
        location.setCode("PM-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        location.setName("POS movement security test");
        location.setActive(true);
        InventoryLocation saved = locationRepository.saveAndFlush(location);

        mockMvc.perform(get("/api/admin/inventory/movements?locationId=" + saved.getId())
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }


    @Test
    void cashierCannotReadOrStartPhysicalStockCounts() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/inventory/stock-counts/current?locationId=1")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/inventory/stock-counts/start")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"reason":"security test"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadCurrentPhysicalStockCount() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/inventory/stock-counts/current?locationId=1")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotAttemptInventoryAdjustmentEvenWithMalformedPayload() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/inventory/adjust")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotOpenStaffAdministrationEndpoints() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/staff")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotAuthorizeAdministratorOnlyDataClearingAction() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/auth/authorize-action")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"password":"test-only-password","action":"CLEAR_ORDERS"}
                  """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void existingJwtUsesUpdatedStaffRoleOnTheNextRequest() throws Exception {
        String username = "role-change-" + java.util.UUID.randomUUID();
        Admin account = new Admin();
        account.setUsername(username);
        account.setPassword("test-only-password");
        account.setRole("CASHIER");
        account.setEnabled(true);
        adminRepository.saveAndFlush(account);
        String existingToken = jwtService.generateToken(username);

        mockMvc.perform(get("/api/admin/pos/sales")
                .header("Authorization", "Bearer " + existingToken))
                .andExpect(status().isForbidden());

        account.setRole("MANAGER");
        adminRepository.saveAndFlush(account);

        mockMvc.perform(get("/api/admin/pos/sales")
                .header("Authorization", "Bearer " + existingToken))
                .andExpect(status().isOk());
    }

    @Test
    void disabledStaffAccountCannotUsePreviouslyIssuedJwt() throws Exception {
        String username = "disabled-" + java.util.UUID.randomUUID();
        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword("test-only-password");
        admin.setEnabled(false);
        adminRepository.saveAndFlush(admin);
        String token = jwtService.generateToken(username);

        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cashierCannotCreatePosRegister() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/registers")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"registerCode":"SECOND","name":"Second Register"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotCreatePosRegister() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/registers")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"registerCode":"SECOND","name":"Second Register"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReachRegisterCreationValidation() throws Exception {
        mockMvc.perform(post("/api/admin/pos/registers")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierCannotReadSalesOrInventoryReports() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/pos/reports/sales?locationId=" + testLocation.getId() + "&from=2026-11-22&to=2026-11-22")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/pos/reports/inventory?locationId=" + testLocation.getId())
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadSalesAndInventoryReports() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/pos/reports/sales?locationId=" + testLocation.getId()
                        + "&from=2026-11-22&to=2026-11-22")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/pos/reports/inventory?locationId=" + testLocation.getId())
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotReadWebhookManualReviewQueue() throws Exception {
        mockMvc.perform(get("/api/admin/pos/payments/webhook-review"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotReadWebhookManualReviewQueue() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/pos/payments/webhook-review")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadWebhookManualReviewQueue() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/pos/payments/webhook-review")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanReadWebhookManualReviewQueue() throws Exception {
        mockMvc.perform(get("/api/admin/pos/payments/webhook-review")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

}

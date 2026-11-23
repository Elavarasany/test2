package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.PosCheckoutRequest;
import nmk_backend.dto.PosPaymentRequest;
import nmk_backend.dto.PosSaleItemRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PosCheckoutServiceTest {

    private PosSaleRepository saleRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private InventoryLocationRepository locationRepository;
    private AdminRepository adminRepository;
    private InventoryService inventoryService;
    private PosShiftService shiftService;
    private ObjectMapper objectMapper;
    private PosCheckoutService service;

    private Product product;
    private ProductVariant variant;
    private InventoryLocation location;
    private Admin cashier;
    private PosShift shift;

    @BeforeEach
    void setUp() {
        saleRepository = mock(PosSaleRepository.class);
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        adminRepository = mock(AdminRepository.class);
        inventoryService = mock(InventoryService.class);
        shiftService = mock(PosShiftService.class);
        objectMapper = new ObjectMapper();

        service = new PosCheckoutService(
                saleRepository,
                productRepository,
                variantRepository,
                locationRepository,
                adminRepository,
                inventoryService,
                shiftService,
                objectMapper
        );

        product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        variant = new ProductVariant();
        variant.setId(20L);
        variant.setProduct(product);
        variant.setSku("FRAME-BLK");
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);

        location = new InventoryLocation();
        location.setId(1L);
        location.setActive(true);

        cashier = new Admin();
        cashier.setId(7L);
        cashier.setUsername("admin");

        PosRegister register = new PosRegister();
        ReflectionTestUtils.setField(register, "id", 3L);
        register.setLocation(location);
        register.setRegisterCode("MAIN");
        shift = new PosShift();
        ReflectionTestUtils.setField(shift, "id", 99L);
        shift.setRegister(register);
        shift.setOpenedByUsername("admin");
        shift.setStatus("OPEN");
        when(shiftService.requireOpenShift(99L, 1L, "admin")).thenReturn(shift);

        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(adminRepository.findByUsername("admin")).thenReturn(Optional.of(cashier));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(variantRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(variant));
        when(saleRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            PosSale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", 123L);
            if (sale.getInvoiceNumber() == null) {
                sale.setInvoiceNumber("NMK-20261119-TEST123456");
            }
            if (sale.getCreatedAt() == null) {
                ReflectionTestUtils.setField(sale, "createdAt", LocalDateTime.now());
            }
            return sale;
        }).when(saleRepository).saveAndFlush(any(PosSale.class));
    }

    @Test
    void checkoutUsesServerPriceAndDeductsInventoryWithinSale() {
        PosCheckoutRequest request = validRequest();

        var response = service.checkout(request, "admin");

        assertEquals("100.00", response.grandTotal().toPlainString());
        assertEquals("100.00", response.items().get(0).unitPrice().toPlainString());
        assertEquals("NMK", response.invoiceNumber().substring(0, 3));
        assertEquals(123L, response.id());

        verify(inventoryService).sellStock(
                eq(1L),
                eq(20L),
                eq(1),
                eq("POS:key-1:20"),
                eq(7L),
                startsWith("POS sale "),
                eq("POS_SALE"),
                eq(123L)
        );
        verify(saleRepository).saveAndFlush(any(PosSale.class));
        verify(shiftService).recordCashSale(
                same(shift), eq(new BigDecimal("100.00")),
                eq("POS-CASH:key-1:0"), eq("admin"), eq(123L)
        );
    }

    @Test
    void sameIdempotencyKeyAndSameRequestReturnsExistingSaleWithoutSellingAgain() throws Exception {
        PosCheckoutRequest request = validRequest();
        String fingerprint = ReflectionTestUtils.invokeMethod(
                service, "fingerprint", request, "admin"
        );
        assertNotNull(fingerprint);

        PosSale existing = new PosSale();
        ReflectionTestUtils.setField(existing, "id", 456L);
        existing.setInvoiceNumber("NMK-20261119-EXISTING");
        existing.setIdempotencyKey("key-1");
        existing.setRequestFingerprint(fingerprint);
        existing.setSubtotal(new BigDecimal("100.00"));
        existing.setDiscountAmount(new BigDecimal("0.00"));
        existing.setGrandTotal(new BigDecimal("100.00"));
        existing.setLocationId(1L);
        existing.setCashierUsername("admin");

        when(saleRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.of(existing));

        var response = service.checkout(request, "admin");

        assertEquals(456L, response.id());
        verify(saleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void reusingIdempotencyKeyWithDifferentRequestIsRejected() {
        PosSale existing = new PosSale();
        existing.setIdempotencyKey("key-1");
        existing.setRequestFingerprint("different-fingerprint");
        when(saleRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(validRequest(), "admin")
        );

        assertTrue(exception.getMessage().contains("different checkout details"));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void reusingIdempotencyKeyWithDifferentShiftIsRejected() {
        PosCheckoutRequest originalRequest = validRequest();
        String originalFingerprint = ReflectionTestUtils.invokeMethod(
                service, "fingerprint", originalRequest, "admin"
        );

        PosSale existing = new PosSale();
        existing.setIdempotencyKey("key-1");
        existing.setRequestFingerprint(originalFingerprint);
        when(saleRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.of(existing));

        PosCheckoutRequest retryWithDifferentShift = validRequest();
        retryWithDifferentShift.setShiftId(100L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(retryWithDifferentShift, "admin")
        );

        assertTrue(exception.getMessage().contains("different checkout details"));
        verifyNoInteractions(inventoryService, locationRepository, shiftService);
    }

    @Test
    void reusingIdempotencyKeyFromAnotherCashierIsRejected() {
        PosCheckoutRequest request = validRequest();
        String originalFingerprint = ReflectionTestUtils.invokeMethod(
                service, "fingerprint", request, "admin"
        );

        PosSale existing = new PosSale();
        existing.setIdempotencyKey("key-1");
        existing.setRequestFingerprint(originalFingerprint);
        when(saleRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(request, "another.cashier")
        );

        assertTrue(exception.getMessage().contains("different checkout details"));
        verifyNoInteractions(inventoryService, locationRepository, shiftService);
    }

    @Test
    void paymentTotalMustEqualGrandTotal() {
        PosCheckoutRequest request = validRequest();
        request.getPayments().get(0).setAmount(new BigDecimal("99.00"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(request, "admin")
        );

        assertTrue(exception.getMessage().contains("exactly equal"));
        verify(saleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void variantMustBelongToRequestedProduct() {
        Product anotherProduct = new Product();
        anotherProduct.setId(999L);
        variant.setProduct(anotherProduct);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(validRequest(), "admin")
        );

        assertTrue(exception.getMessage().contains("does not belong"));
        verify(saleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }


    @Test
    void supportsSplitPaymentsAndCalculatesCashChange() {
        PosCheckoutRequest request = validRequest();
        request.setDiscountAmount(new BigDecimal("10.00"));

        PosPaymentRequest cash = new PosPaymentRequest();
        cash.setPaymentMethod("CASH");
        cash.setAmount(new BigDecimal("40.00"));
        cash.setTenderedAmount(new BigDecimal("50.00"));

        PosPaymentRequest upi = new PosPaymentRequest();
        upi.setPaymentMethod("UPI");
        upi.setAmount(new BigDecimal("50.00"));
        upi.setTransactionReference("UPI-UTR-123456");
        request.setPayments(List.of(cash, upi));

        var response = service.checkout(request, "admin", "ADMIN");

        assertEquals("90.00", response.grandTotal().toPlainString());
        assertEquals(2, response.payments().size());
        assertEquals("10.00", response.payments().get(0).changeAmount().toPlainString());
        assertEquals("0.00", response.payments().get(1).changeAmount().toPlainString());
    }

    @Test
    void rejectsUpiPaymentWithoutTransactionReferenceBeforePersisting() {
        PosCheckoutRequest request = validRequest();
        PosPaymentRequest upi = new PosPaymentRequest();
        upi.setPaymentMethod("UPI");
        upi.setAmount(new BigDecimal("100.00"));
        request.setPayments(List.of(upi));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(request, "admin", "ADMIN")
        );

        assertTrue(exception.getMessage().contains("transaction reference"));
        verify(saleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void rejectsDiscountGreaterThanSubtotalBeforePersisting() {
        PosCheckoutRequest request = validRequest();
        request.setDiscountAmount(new BigDecimal("101.00"));

        assertThrows(IllegalArgumentException.class,
                () -> service.checkout(request, "admin", "ADMIN"));

        verify(saleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void rejectsDuplicateVariantLines() {
        PosCheckoutRequest request = validRequest();
        PosSaleItemRequest duplicate = new PosSaleItemRequest();
        duplicate.setProductId(10L);
        duplicate.setVariantId(20L);
        duplicate.setQuantity(2);
        request.setItems(List.of(request.getItems().get(0), duplicate));

        assertThrows(IllegalArgumentException.class,
                () -> service.checkout(request, "admin"));

        verify(saleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }


    @Test
    void cashierCannotApplySaleDiscount() {
        PosCheckoutRequest request = validRequest();
        request.setDiscountAmount(new BigDecimal("10.00"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(request, "admin", "CASHIER")
        );

        assertTrue(exception.getMessage().contains("Only ADMIN or MANAGER"));
        verifyNoInteractions(saleRepository, locationRepository, shiftService, inventoryService);
    }

    @Test
    void cashierCanCompleteSaleWithoutDiscount() {
        var response = service.checkout(validRequest(), "admin", "CASHIER");
        assertEquals("100.00", response.grandTotal().toPlainString());
        verify(saleRepository).saveAndFlush(any(PosSale.class));
    }

    private PosCheckoutRequest validRequest() {
        PosCheckoutRequest request = new PosCheckoutRequest();
        request.setLocationId(1L);
        request.setShiftId(99L);
        request.setIdempotencyKey("key-1");
        request.setDiscountAmount(new BigDecimal("0.00"));

        PosSaleItemRequest item = new PosSaleItemRequest();
        item.setProductId(10L);
        item.setVariantId(20L);
        item.setQuantity(1);
        request.setItems(List.of(item));

        PosPaymentRequest payment = new PosPaymentRequest();
        payment.setPaymentMethod("CASH");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setTenderedAmount(new BigDecimal("120.00"));
        request.setPayments(List.of(payment));
        return request;
    }
}

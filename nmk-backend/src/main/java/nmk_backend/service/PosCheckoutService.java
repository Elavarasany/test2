package nmk_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.PosCheckoutRequest;
import nmk_backend.dto.PosPaymentRequest;
import nmk_backend.dto.PosSaleItemRequest;
import nmk_backend.dto.PosSaleResponse;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.PosSaleRepository;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Comparator;
import java.util.Set;

@Service
public class PosCheckoutService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "UPI", "CARD");

    private final PosSaleRepository saleRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryLocationRepository locationRepository;
    private final AdminRepository adminRepository;
    private final InventoryService inventoryService;
    private final PosShiftService shiftService;
    private final ObjectMapper objectMapper;
    private final AdminLocationAccessService locationAccessService;

    public PosCheckoutService(
            PosSaleRepository saleRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryLocationRepository locationRepository,
            AdminRepository adminRepository,
            InventoryService inventoryService,
            PosShiftService shiftService,
            ObjectMapper objectMapper
    ) {
        this(saleRepository, productRepository, variantRepository, locationRepository,
                adminRepository, inventoryService, shiftService, objectMapper, null);
    }

    @Autowired
    public PosCheckoutService(
            PosSaleRepository saleRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryLocationRepository locationRepository,
            AdminRepository adminRepository,
            InventoryService inventoryService,
            PosShiftService shiftService,
            ObjectMapper objectMapper,
            AdminLocationAccessService locationAccessService
    ) {
        this.saleRepository = saleRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.locationRepository = locationRepository;
        this.adminRepository = adminRepository;
        this.inventoryService = inventoryService;
        this.shiftService = shiftService;
        this.objectMapper = objectMapper;
        this.locationAccessService = locationAccessService;
    }

    @Transactional(readOnly = true)
    public Page<PosSaleResponse> getSales(Long locationId, Pageable pageable) {
        return getSales(locationId, pageable, null, null);
    }

    @Transactional(readOnly = true)
    public Page<PosSaleResponse> getSales(Long locationId, Pageable pageable, String username, String role) {
        Page<PosSale> sales;
        if (locationId != null) {
            if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, locationId);
            sales = saleRepository.findByLocationId(locationId, pageable);
        } else if (locationAccessService != null && !"ADMIN".equalsIgnoreCase(role)) {
            var allowedLocations = locationAccessService.getAccessibleLocationIds(username, role);
            sales = allowedLocations.isEmpty() ? Page.empty(pageable)
                    : saleRepository.findByLocationIdIn(allowedLocations, pageable);
        } else {
            sales = saleRepository.findAll(pageable);
        }
        return sales.map(PosSaleResponse::from);
    }

    @Transactional(readOnly = true)
    public PosSaleResponse getSale(Long saleId) {
        return getSale(saleId, null, null);
    }

    @Transactional(readOnly = true)
    public PosSaleResponse getSale(Long saleId, String username, String role) {
        PosSale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new InventoryNotFoundException("POS sale was not found"));
        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, sale.getLocationId());
        return PosSaleResponse.from(sale);
    }

    @Transactional
    public PosSaleResponse checkout(
            PosCheckoutRequest request,
            String authenticatedUsername
    ) {
        return checkout(request, authenticatedUsername, "CASHIER");
    }

    @Transactional
    public PosSaleResponse checkout(
            PosCheckoutRequest request,
            String authenticatedUsername,
            String authenticatedRole
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Checkout request is required");
        }
        if (authenticatedUsername == null || authenticatedUsername.isBlank()) {
            throw new IllegalArgumentException("Authenticated cashier is required");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("At least one sale item is required");
        }
        if (request.getPayments() == null || request.getPayments().isEmpty()) {
            throw new IllegalArgumentException("At least one payment is required");
        }
        if (request.getPayments().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Payment rows cannot be null");
        }
        if (request.getItems().size() > 100) {
            throw new IllegalArgumentException("A checkout cannot contain more than 100 item lines");
        }
        if (request.getPayments().size() > 3) {
            throw new IllegalArgumentException("A checkout can contain at most three payment methods");
        }
        if (request.getItems().stream().anyMatch(item ->
                item == null || item.getProductId() == null || item.getProductId() <= 0
                        || item.getVariantId() == null || item.getVariantId() <= 0
                        || item.getQuantity() == null || item.getQuantity() <= 0)) {
            throw new IllegalArgumentException(
                    "Every sale item needs positive product, variant and quantity values"
            );
        }

        String role = authenticatedRole == null
                ? "" : authenticatedRole.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ADMIN", "MANAGER", "CASHIER").contains(role)) {
            throw new IllegalArgumentException("A valid POS staff role is required");
        }
        BigDecimal discount = money(request.getDiscountAmount(), "Discount");
        if (discount.compareTo(ZERO) < 0) {
            throw new IllegalArgumentException("Discount cannot be negative");
        }
        if (discount.compareTo(ZERO) > 0 && "CASHIER".equals(role)) {
            throw new IllegalArgumentException("Only ADMIN or MANAGER staff may apply a sale discount");
        }

        if (locationAccessService != null) {
            locationAccessService.assertCanAccess(authenticatedUsername, role, request.getLocationId());
        }

        String idempotencyKey = normalizeIdempotencyKey(request.getIdempotencyKey());
        String fingerprint = fingerprint(request, authenticatedUsername);

        var previousSale = saleRepository.findByIdempotencyKey(idempotencyKey);
        if (previousSale.isPresent()) {
            PosSale existing = previousSale.get();
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new IllegalArgumentException(
                        "This idempotency key was already used with different checkout details"
                );
            }
            return PosSaleResponse.from(existing);
        }

        InventoryLocation location = locationRepository.findByIdForUpdate(request.getLocationId())
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory location was not found"
                ));
        if (!location.isActive()) {
            throw new IllegalArgumentException("Inventory location is inactive");
        }

        // A sale must belong to the authenticated cashier's open shift at this location.
        // The shift row remains locked until the sale and its cash ledger entries commit.
        PosShift shift = shiftService.requireOpenShift(
                request.getShiftId(), location.getId(), authenticatedUsername
        );

        // Recheck after acquiring the location lock. This closes the common
        // same-location race where two retries both initially observed no sale.
        var saleAfterLock = saleRepository.findByIdempotencyKeyForUpdate(idempotencyKey);
        if (saleAfterLock.isPresent()) {
            PosSale existing = saleAfterLock.get();
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new IllegalArgumentException(
                        "This idempotency key was already used with different checkout details"
                );
            }
            return PosSaleResponse.from(existing);
        }

        Admin cashier = adminRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Authenticated admin account was not found"
                ));

        validateCustomer(request);
        PosSale sale = new PosSale();
        sale.setLocationId(location.getId());
        sale.setShiftId(shift.getId());
        sale.setCashierUsername(authenticatedUsername);
        sale.setCustomerName(trimToNull(request.getCustomerName()));
        sale.setCustomerPhone(trimToNull(request.getCustomerPhone()));
        sale.setDiscountAmount(discount);
        sale.setIdempotencyKey(idempotencyKey);
        sale.setRequestFingerprint(fingerprint);

        BigDecimal subtotal = ZERO;
        Set<Long> seenVariantIds = new HashSet<>();

        var sortedItems = request.getItems().stream()
                .sorted(Comparator.comparing(PosSaleItemRequest::getProductId)
                        .thenComparing(PosSaleItemRequest::getVariantId))
                .toList();

        for (PosSaleItemRequest itemRequest : sortedItems) {
            if (itemRequest == null
                    || itemRequest.getProductId() == null
                    || itemRequest.getVariantId() == null
                    || itemRequest.getQuantity() == null
                    || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("Every sale item needs valid product, variant and quantity values");
            }
            if (!seenVariantIds.add(itemRequest.getVariantId())) {
                throw new IllegalArgumentException(
                        "A product variant can appear only once in a checkout"
                );
            }

            Product product = productRepository.findByIdForUpdate(itemRequest.getProductId())
                    .orElseThrow(() -> new InventoryNotFoundException(
                            "Product " + itemRequest.getProductId() + " was not found"
                    ));
            ProductVariant variant = variantRepository.findByIdForUpdate(itemRequest.getVariantId())
                    .orElseThrow(() -> new InventoryNotFoundException(
                            "Product variant " + itemRequest.getVariantId() + " was not found"
                    ));

            if (!variant.getProduct().getId().equals(product.getId())) {
                throw new IllegalArgumentException(
                        "The selected variant does not belong to the selected product"
                );
            }
            if (!product.isActive() || !variant.isActive()) {
                throw new IllegalArgumentException(
                        "Inactive products or variants cannot be sold"
                );
            }

            BigDecimal unitPrice = variant.getSellingPriceOverride() != null
                    ? variant.getSellingPriceOverride()
                    : product.getPrice();
            if (unitPrice == null || unitPrice.compareTo(ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "Product has no valid selling price: " + product.getName()
                );
            }
            unitPrice = unitPrice.setScale(2);
            BigDecimal lineTotal = unitPrice.multiply(
                    BigDecimal.valueOf(itemRequest.getQuantity())
            ).setScale(2);
            subtotal = subtotal.add(lineTotal);

            PosSaleItem saleItem = new PosSaleItem();
            saleItem.setSale(sale);
            saleItem.setProductId(product.getId());
            saleItem.setVariantId(variant.getId());
            saleItem.setSku(variant.getSku());
            saleItem.setProductName(product.getName());
            saleItem.setVariantName(variant.getVariantName());
            saleItem.setVariantAttributesJson(serializeAttributes(variant));
            saleItem.setQuantity(itemRequest.getQuantity());
            saleItem.setUnitPrice(unitPrice);
            saleItem.setLineTotal(lineTotal);
            sale.getItems().add(saleItem);
        }

        if (discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("Discount cannot exceed the sale subtotal");
        }

        BigDecimal grandTotal = subtotal.subtract(discount).setScale(2);
        if (grandTotal.compareTo(ZERO) < 0) {
            throw new IllegalArgumentException("Sale total cannot be negative");
        }

        validateAndAttachPayments(request, sale, grandTotal);

        sale.setSubtotal(subtotal.setScale(2));
        sale.setGrandTotal(grandTotal);

        // Flush first to obtain the immutable sale ID used by the stock movement audit trail.
        saleRepository.saveAndFlush(sale);

        int paymentIndex = 0;
        for (PosPayment payment : sale.getPayments()) {
            if ("CASH".equals(payment.getPaymentMethod())) {
                shiftService.recordCashSale(
                        shift,
                        payment.getAmount(),
                        "POS-CASH:" + idempotencyKey + ":" + paymentIndex,
                        authenticatedUsername,
                        sale.getId()
                );
            }
            paymentIndex++;
        }

        for (PosSaleItem item : sale.getItems()) {
            String movementKey = "POS:" + idempotencyKey + ":" + item.getVariantId();
            inventoryService.sellStock(
                    location.getId(),
                    item.getVariantId(),
                    item.getQuantity(),
                    movementKey,
                    cashier.getId(),
                    "POS sale " + sale.getInvoiceNumber(),
                    "POS_SALE",
                    sale.getId()
            );
        }

        return PosSaleResponse.from(sale);
    }

    private void validateAndAttachPayments(
            PosCheckoutRequest request,
            PosSale sale,
            BigDecimal grandTotal
    ) {
        BigDecimal paymentTotal = ZERO;
        Set<String> seenPaymentMethods = new HashSet<>();

        for (PosPaymentRequest paymentRequest : request.getPayments()) {
            if (paymentRequest == null || paymentRequest.getPaymentMethod() == null) {
                throw new IllegalArgumentException("Every payment needs a payment method");
            }

            String method = paymentRequest.getPaymentMethod()
                    .trim().toUpperCase(Locale.ROOT);
            if (!PAYMENT_METHODS.contains(method)) {
                throw new IllegalArgumentException(
                        "Payment method must be CASH, UPI or CARD. Use multiple payment rows for split payments."
                );
            }
            if (!seenPaymentMethods.add(method)) {
                throw new IllegalArgumentException(
                        "Each payment method can appear only once in a split payment"
                );
            }

            BigDecimal amount = money(paymentRequest.getAmount(), "Payment amount");
            if (amount.compareTo(ZERO) <= 0) {
                throw new IllegalArgumentException("Payment amount must be greater than zero");
            }

            BigDecimal tendered = paymentRequest.getTenderedAmount();
            BigDecimal change = ZERO;
            if ("CASH".equals(method)) {
                if (tendered == null || tendered.compareTo(amount) < 0) {
                    throw new IllegalArgumentException(
                            "Cash tendered must be greater than or equal to the cash payment amount"
                    );
                }
                tendered = money(tendered, "Cash tendered");
                change = tendered.subtract(amount).setScale(2);
            } else if (tendered != null) {
                throw new IllegalArgumentException(
                        "Tendered amount is only supported for cash payments"
                );
            }

            String reference = trimToNull(paymentRequest.getTransactionReference());
            if (!"CASH".equals(method) && (reference == null || reference.isBlank())) {
                throw new IllegalArgumentException("A transaction reference is required for UPI and card payments");
            }
            if (reference != null && reference.length() > 150) {
                throw new IllegalArgumentException("Payment reference cannot exceed 150 characters");
            }

            PosPayment payment = new PosPayment();
            payment.setSale(sale);
            payment.setPaymentMethod(method);
            payment.setAmount(amount);
            payment.setTenderedAmount(tendered);
            payment.setChangeAmount(change);
            payment.setTransactionReference(reference);
            sale.getPayments().add(payment);

            paymentTotal = paymentTotal.add(amount);
        }

        if (paymentTotal.compareTo(grandTotal) != 0) {
            throw new IllegalArgumentException(
                    "Payment amounts must exactly equal the sale total"
            );
        }
    }

    private void validateCustomer(PosCheckoutRequest request) {
        String name = trimToNull(request.getCustomerName());
        String phone = trimToNull(request.getCustomerPhone());
        if (name != null && name.length() > 150) {
            throw new IllegalArgumentException("Customer name cannot exceed 150 characters");
        }
        if (phone != null && phone.length() > 30) {
            throw new IllegalArgumentException("Customer phone cannot exceed 30 characters");
        }
    }

    private BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " can have at most 2 decimal places");
        }
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 100) {
            throw new IllegalArgumentException(
                    "A checkout idempotency key of 1 to 100 characters is required"
            );
        }
        return key.trim();
    }

    private String fingerprint(PosCheckoutRequest request, String authenticatedUsername) {
        try {
            var items = request.getItems().stream()
                    .sorted(Comparator.comparing(PosSaleItemRequest::getProductId)
                            .thenComparing(PosSaleItemRequest::getVariantId))
                    .map(item -> new CheckoutFingerprintItem(
                            item.getProductId(),
                            item.getVariantId(),
                            item.getQuantity()
                    ))
                    .toList();

            var payments = request.getPayments().stream()
                    .map(payment -> new CheckoutFingerprintPayment(
                            payment.getPaymentMethod() == null
                                    ? null
                                    : payment.getPaymentMethod().trim().toUpperCase(Locale.ROOT),
                            canonicalAmount(payment.getAmount()),
                            canonicalAmount(payment.getTenderedAmount()),
                            trimToNull(payment.getTransactionReference())
                    ))
                    .sorted(Comparator
                            .comparing(CheckoutFingerprintPayment::paymentMethod,
                                    Comparator.nullsFirst(String::compareTo))
                            .thenComparing(CheckoutFingerprintPayment::amount,
                                    Comparator.nullsFirst(BigDecimal::compareTo))
                            .thenComparing(CheckoutFingerprintPayment::tenderedAmount,
                                    Comparator.nullsFirst(BigDecimal::compareTo))
                            .thenComparing(CheckoutFingerprintPayment::transactionReference,
                                    Comparator.nullsFirst(String::compareTo)))
                    .toList();

            CheckoutFingerprint canonicalRequest = new CheckoutFingerprint(
                    request.getLocationId(),
                    request.getShiftId(),
                    authenticatedUsername.trim().toLowerCase(Locale.ROOT),
                    trimToNull(request.getCustomerName()),
                    trimToNull(request.getCustomerPhone()),
                    canonicalAmount(request.getDiscountAmount()),
                    items,
                    payments
            );

            byte[] bytes = objectMapper.writeValueAsBytes(canonicalRequest);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint POS checkout request", exception);
        }
    }

    private BigDecimal canonicalAmount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private record CheckoutFingerprint(
            Long locationId,
            Long shiftId,
            String cashierUsername,
            String customerName,
            String customerPhone,
            BigDecimal discountAmount,
            java.util.List<CheckoutFingerprintItem> items,
            java.util.List<CheckoutFingerprintPayment> payments
    ) {
    }

    private record CheckoutFingerprintItem(
            Long productId,
            Long variantId,
            Integer quantity
    ) {
    }

    private record CheckoutFingerprintPayment(
            String paymentMethod,
            BigDecimal amount,
            BigDecimal tenderedAmount,
            String transactionReference
    ) {
    }

    private String serializeAttributes(ProductVariant variant) {
        try {
            return objectMapper.writeValueAsString(variant.getAttributes());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to snapshot product variant attributes", exception);
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}


package nmk_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nmk_backend.dto.CreateOrderRequest;
import nmk_backend.dto.OrderItemRequest;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.entity.Order;
import nmk_backend.entity.OrderItem;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class OrderService {

    private static final String VARIANT_INVENTORY = "VARIANT_INVENTORY";
    private static final String LEGACY_PRODUCT = "LEGACY_PRODUCT";
    private static final String ORDER_REFERENCE = "ORDER";

    private static final ObjectMapper PRESCRIPTION_WRITER =
            new ObjectMapper();

    private static final ObjectMapper VARIANT_WRITER =
            new ObjectMapper();

    private static final List<String> EYE_FIELDS =
            List.of("sph", "cyl", "axis", "add");

    private static final List<String> CONTACT_EYE_FIELDS =
            List.of("sph", "cyl", "axis", "boxes");

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryLocationRepository locationRepository;
    private final InventoryService inventoryService;
    private final String checkoutLocationCode;
    private final PrescriptionCloudinaryService prescriptionStorage;

    @Autowired
    public OrderService(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryLocationRepository locationRepository,
            InventoryService inventoryService,
            PrescriptionCloudinaryService prescriptionStorage,
            @Value("${inventory.checkout.location-code:}") String checkoutLocationCode
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.locationRepository = locationRepository;
        this.inventoryService = inventoryService;
        this.prescriptionStorage = prescriptionStorage;
        this.checkoutLocationCode = checkoutLocationCode;
    }

    // Retained for focused legacy unit tests that do not exercise uploaded assets.
    public OrderService(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryLocationRepository locationRepository,
            InventoryService inventoryService,
            String checkoutLocationCode
    ) {
        this(orderRepository, productRepository, variantRepository, locationRepository,
                inventoryService, null, checkoutLocationCode);
    }

    @Transactional
    public Order createPendingOrder(CreateOrderRequest request) {

        if (request == null
                || request.getItems() == null
                || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        if (checkoutLocationCode == null
                || checkoutLocationCode.isBlank()) {
            throw new IllegalStateException(
                    "Checkout inventory location is not configured."
            );
        }

        InventoryLocation location = locationRepository
                .findByCode(checkoutLocationCode.trim())
                .orElseThrow(() -> new IllegalStateException(
                        "Configured checkout inventory location was not found."
                ));

        // Lock location first. InventoryService uses the same lock order.
        location = locationRepository.findByIdForUpdate(location.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Checkout inventory location was not found."
                ));

        if (!location.isActive()) {
            throw new IllegalStateException(
                    "Checkout inventory location is inactive."
            );
        }

        /*
         * Resolve all requested variant parent IDs first.
         * Lock products and variants in deterministic order to reduce
         * deadlock risk when a cart contains multiple variants.
         */
        Map<Long, Long> variantProductIds = new TreeMap<>();

        for (OrderItemRequest itemRequest : request.getItems()) {

            validateCartItem(itemRequest);

            Long variantId = itemRequest.getVariantId();

            Long parentProductId = variantRepository
                    .findProductIdByVariantId(variantId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Product variant not found."
                    ));

            if (!parentProductId.equals(itemRequest.getProductId())) {
                throw new IllegalArgumentException(
                        "Selected variant does not belong to the requested product."
                );
            }

            variantProductIds.put(variantId, parentProductId);
        }

        // Lock parent products in ascending ID order.
        Map<Long, Product> lockedProducts = new TreeMap<>();

        variantProductIds.values().stream()
                .distinct()
                .sorted()
                .forEach(productId -> {
                    Product product = productRepository
                            .findByIdForUpdate(productId)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Product not found."
                                    )
                            );

                    if (!product.isActive()) {
                        throw new IllegalArgumentException(
                                product.getName() + " is unavailable."
                        );
                    }

                    lockedProducts.put(productId, product);
                });

        // Lock variants in ascending variant ID order.
        Map<Long, ProductVariant> lockedVariants = new TreeMap<>();

        for (Map.Entry<Long, Long> entry : variantProductIds.entrySet()) {

            ProductVariant variant = variantRepository
                    .findByIdForUpdate(entry.getKey())
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Product variant not found."
                            )
                    );

            if (!variant.isActive()) {
                throw new IllegalArgumentException(
                        "Selected product variant is unavailable."
                );
            }

            if (variant.getProduct() == null
                    || !entry.getValue().equals(
                    variant.getProduct().getId()
            )) {
                throw new IllegalArgumentException(
                        "Product variant relationship is invalid."
                );
            }

            lockedVariants.put(entry.getKey(), variant);
        }

        Order order = new Order();

        order.setCustomerName(request.getCustomerName());
        order.setEmail(request.getEmail());
        order.setPhone(request.getPhone());
        order.setAddress(request.getAddress());
        order.setCity(request.getCity());
        order.setState(request.getState());
        order.setPincode(request.getPincode());
        order.setPaymentStatus("PENDING");
        order.setReservationType(VARIANT_INVENTORY);
        order.setInventoryLocationId(location.getId());

        BigDecimal total = BigDecimal.ZERO;

        List<OrderItem> orderItems = new ArrayList<>();

        // Aggregate requested stock by variant ID.
        Map<Long, Integer> reservedQuantities = new TreeMap<>();

        for (OrderItemRequest itemRequest : request.getItems()) {

            ProductVariant variant = lockedVariants.get(
                    itemRequest.getVariantId()
            );

            Product product = lockedProducts.get(
                    variantProductIds.get(itemRequest.getVariantId())
            );

            if (variant == null || product == null) {
                throw new IllegalStateException(
                        "Unable to resolve selected product variant."
                );
            }

            reservedQuantities.merge(
                    variant.getId(),
                    itemRequest.getQuantity(),
                    (current, added) -> {
                        try {
                            return Math.addExact(current, added);
                        } catch (ArithmeticException exception) {
                            throw new IllegalArgumentException(
                                    "Requested quantity is too large."
                            );
                        }
                    }
            );

            BigDecimal basePrice = variant.getSellingPriceOverride() != null
                    ? variant.getSellingPriceOverride()
                    : product.getPrice();

            if (basePrice == null || basePrice.signum() < 0) {
                throw new IllegalStateException(
                        "Product price is invalid."
                );
            }

            BigDecimal unitPrice = basePrice;

            boolean isEyeglasses =
                    "Eyeglasses".equalsIgnoreCase(product.getCategory());

            String prescription = itemRequest.getPrescriptionType();
            String lens = itemRequest.getLensType();

            boolean photochromic = Boolean.TRUE.equals(
                    itemRequest.getPhotochromic()
            );

            if (isEyeglasses) {

                if (prescription == null || prescription.isBlank()) {
                    throw new IllegalArgumentException(
                            "Please select a Power Type for Eyeglasses."
                    );
                }

                BigDecimal lensPrice = BigDecimal.ZERO;

                switch (prescription) {

                    case "Frame Only":
                        lensPrice = BigDecimal.ZERO;
                        break;

                    case "Single Vision":
                    case "Progressive":
                    case "Bifocal":

                        if (lens == null || lens.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Please select a lens type."
                            );
                        }

                        switch (prescription) {

                            case "Single Vision":
                                switch (lens) {
                                    case "ClearGuard":
                                        lensPrice = BigDecimal.valueOf(899);
                                        break;
                                    case "ClearGuard Blue":
                                        lensPrice = BigDecimal.valueOf(1649);
                                        break;
                                    case "ClearGuard Plus":
                                        lensPrice = BigDecimal.valueOf(1989);
                                        break;
                                    case "ClearGuard NightDrive":
                                        lensPrice = BigDecimal.valueOf(2899);
                                        break;
                                    default:
                                        throw new IllegalArgumentException(
                                                "Invalid lens type."
                                        );
                                }

                                if (photochromic) {
                                    lensPrice = lensPrice.add(
                                            BigDecimal.valueOf(689)
                                    );
                                }
                                break;

                            case "Progressive":
                                switch (lens) {
                                    case "ClearGuard":
                                        lensPrice = BigDecimal.valueOf(1799);
                                        break;
                                    case "ClearGuard Blue":
                                        lensPrice = BigDecimal.valueOf(2449);
                                        break;
                                    case "ClearGuard Plus":
                                        lensPrice = BigDecimal.valueOf(2989);
                                        break;
                                    case "ClearGuard NightDrive":
                                        lensPrice = BigDecimal.valueOf(3899);
                                        break;
                                    default:
                                        throw new IllegalArgumentException(
                                                "Invalid lens type."
                                        );
                                }

                                if (photochromic) {
                                    lensPrice = lensPrice.add(
                                            BigDecimal.valueOf(989)
                                    );
                                }
                                break;

                            case "Bifocal":
                                switch (lens) {
                                    case "ClearGuard":
                                        lensPrice = BigDecimal.valueOf(1399);
                                        break;
                                    case "ClearGuard Blue":
                                        lensPrice = BigDecimal.valueOf(1849);
                                        break;
                                    case "ClearGuard Plus":
                                        lensPrice = BigDecimal.valueOf(2489);
                                        break;
                                    case "ClearGuard NightDrive":
                                        lensPrice = BigDecimal.valueOf(2999);
                                        break;
                                    default:
                                        throw new IllegalArgumentException(
                                                "Invalid lens type."
                                        );
                                }

                                if (photochromic) {
                                    lensPrice = lensPrice.add(
                                            BigDecimal.valueOf(989)
                                    );
                                }
                                break;
                        }
                        break;

                    default:
                        throw new IllegalArgumentException(
                                "Invalid Power Type."
                        );
                }

                unitPrice = unitPrice.add(lensPrice);

            } else if (
                    prescription != null
                            || lens != null
                            || photochromic
            ) {
                throw new IllegalArgumentException(
                        "Lens customization is only available for Eyeglasses."
                );
            }

            BigDecimal itemTotal = unitPrice.multiply(
                    BigDecimal.valueOf(itemRequest.getQuantity())
            );

            total = total.add(itemTotal);

            OrderItem orderItem = new OrderItem();

            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setQuantity(itemRequest.getQuantity());
            orderItem.setPrice(unitPrice);

            // Store the selected variant snapshot.
            orderItem.setVariantId(variant.getId());
            orderItem.setVariantSku(variant.getSku());
            orderItem.setVariantName(variant.getVariantName());

            try {
                String attributesJson = VARIANT_WRITER.writeValueAsString(
                        variant.getAttributes()
                );

                if (attributesJson.length() > 4000) {
                    throw new IllegalArgumentException(
                            "Variant attributes are too large to save."
                    );
                }

                orderItem.setVariantAttributesJson(attributesJson);

            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                        "Unable to save selected variant details.",
                        exception
                );
            }

            if (isEyeglasses) {

                orderItem.setPrescriptionType(prescription);

                if ("Frame Only".equals(prescription)) {
                    orderItem.setLensType(null);
                    orderItem.setPhotochromic(false);
                } else {
                    orderItem.setLensType(lens);
                    orderItem.setPhotochromic(photochromic);
                }
            }

            orderItem.setPrescriptionJson(
                    toPrescriptionJson(
                            itemRequest.getPrescription(),
                            itemRequest.getPrescriptionFileName(),
                            itemRequest.getLensKind()
                    )
            );

            orderItem.setOrder(order);
            orderItems.add(orderItem);
        }

        order.setTotalAmount(total);
        order.setItems(orderItems);

        order.setStockReserved(true);
        order.setReservationExpiresAt(
                LocalDateTime.now().plusMinutes(15)
        );

        /*
         * Flush the order first to obtain its database ID.
         * Inventory movements use this ID as their audit reference.
         */
        Order savedOrder = orderRepository.saveAndFlush(order);

        // Reserve variant stock in deterministic order.
        for (Map.Entry<Long, Integer> entry :
                reservedQuantities.entrySet()) {

            Long variantId = entry.getKey();
            int quantity = entry.getValue();

            String idempotencyKey =
                    "ORDER-" + savedOrder.getId()
                            + "-VARIANT-" + variantId
                            + "-RESERVE";

            inventoryService.reserveStock(
                    location.getId(),
                    variantId,
                    quantity,
                    idempotencyKey,
                    null,
                    "Customer checkout stock reservation",
                    ORDER_REFERENCE,
                    savedOrder.getId()
            );
        }

        return savedOrder;
    }

    private void validateCartItem(OrderItemRequest itemRequest) {

        if (itemRequest == null
                || itemRequest.getProductId() == null
                || itemRequest.getProductId() <= 0
                || itemRequest.getVariantId() == null
                || itemRequest.getVariantId() <= 0
                || itemRequest.getQuantity() == null
                || itemRequest.getQuantity() <= 0) {

            throw new IllegalArgumentException(
                    "Each cart item must contain a valid product, variant and quantity."
            );
        }
    }

    @Transactional
    public void releaseReservation(Long orderId) {

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Order not found.")
                );

        if ("IN_PROGRESS".equalsIgnoreCase(
                order.getPaymentCreationStatus()
        )) {
            throw new IllegalStateException(
                    "Cannot release stock while Razorpay order creation is in progress."
            );
        }

        releaseReservationForOrder(order);
    }

    @Transactional
    public void cancelPendingOrder(Long orderId) {

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Order not found.")
                );

        if (!"PENDING".equalsIgnoreCase(order.getOrderStatus())) {
            throw new IllegalStateException(
                    "Only pending orders can be cancelled through this action."
            );
        }

        if (!"PENDING".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new IllegalStateException(
                    "This order is not an unpaid pending order."
            );
        }

        String creationStatus = order.getPaymentCreationStatus();

        if ("IN_PROGRESS".equalsIgnoreCase(creationStatus)
                || "CREATED".equalsIgnoreCase(creationStatus)) {
            throw new IllegalStateException(
                    "Payment creation has started. Verify payment status before cancelling this order."
            );
        }

        if (order.isStockReserved()) {
            releaseReservationForOrder(order);
        } else {
            order.setPaymentStatus("FAILED");
            order.setOrderStatus("CANCELLED");
            order.setReservationExpiresAt(null);
            orderRepository.save(order);
        }
    }

    private void releaseReservationForOrder(Order order) {

        if (!order.isStockReserved()) {
            return;
        }

        String reservationType = order.getReservationType();

        if (VARIANT_INVENTORY.equalsIgnoreCase(
                reservationType == null ? "" : reservationType
        )) {
            releaseVariantReservation(order);

        } else if (reservationType == null
                || reservationType.isBlank()
                || LEGACY_PRODUCT.equalsIgnoreCase(reservationType)) {
            releaseLegacyProductReservation(order);

        } else {
            throw new IllegalStateException(
                    "Unknown reservation type. Stock was not released."
            );
        }

        order.setStockReserved(false);
        order.setReservationExpiresAt(null);
        order.setPaymentStatus("FAILED");
        order.setOrderStatus("CANCELLED");

        orderRepository.save(order);
    }

    private void releaseVariantReservation(Order order) {

        if (order.getInventoryLocationId() == null) {
            throw new IllegalStateException(
                    "Order has no inventory location. Manual reconciliation is required."
            );
        }

        Map<Long, Integer> quantities = new TreeMap<>();

        for (OrderItem item : order.getItems()) {

            if (item.getVariantId() == null
                    || item.getQuantity() == null
                    || item.getQuantity() <= 0) {
                throw new IllegalStateException(
                        "Order contains incomplete variant reservation details."
                );
            }

            quantities.merge(
                    item.getVariantId(),
                    item.getQuantity(),
                    (current, added) -> {
                        try {
                            return Math.addExact(current, added);
                        } catch (ArithmeticException exception) {
                            throw new IllegalStateException(
                                    "Order quantity exceeds the supported limit."
                            );
                        }
                    }
            );
        }

        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {

            Long variantId = entry.getKey();

            String idempotencyKey =
                    "ORDER-" + order.getId()
                            + "-VARIANT-" + variantId
                            + "-RELEASE";

            inventoryService.releaseReservation(
                    order.getInventoryLocationId(),
                    variantId,
                    entry.getValue(),
                    idempotencyKey,
                    null,
                    "Customer order reservation released",
                    ORDER_REFERENCE,
                    order.getId()
            );
        }
    }

    private void releaseLegacyProductReservation(Order order) {

        Map<Long, Integer> quantities = new TreeMap<>();

        for (OrderItem item : order.getItems()) {

            if (item.getProductId() == null
                    || item.getQuantity() == null
                    || item.getQuantity() <= 0) {
                throw new IllegalStateException(
                        "Legacy order contains incomplete product reservation details."
                );
            }

            quantities.merge(
                    item.getProductId(),
                    item.getQuantity(),
                    (current, added) -> {
                        try {
                            return Math.addExact(current, added);
                        } catch (ArithmeticException exception) {
                            throw new IllegalStateException(
                                    "Order quantity exceeds the supported limit."
                            );
                        }
                    }
            );
        }

        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {

            int updatedRows = productRepository.releaseStock(
                    entry.getKey(),
                    entry.getValue()
            );

            if (updatedRows != 1) {
                throw new IllegalStateException(
                        "Could not release legacy stock for product ID: "
                                + entry.getKey()
                );
            }
        }
    }

    // Prescription JSON saving.

    private String toPrescriptionJson(
            Map<String, Object> rx,
            String fileName,
            String lensKind
    ) {

        if (rx == null || rx.isEmpty()) {
            return null;
        }

        validatePrescriptionPayload(rx);

        if (fileName != null && fileName.length() > 255) {
            throw new IllegalArgumentException(
                    "Prescription file name is too long."
            );
        }

        Map<String, Object> out = new LinkedHashMap<>();

        if ("image".equalsIgnoreCase(text(rx.get("mode")))) {
            String publicId = urlText(rx.get("publicId"));
            if (!publicId.isBlank()) {
                if (prescriptionStorage == null || !prescriptionStorage.verifyUploadedAsset(rx)) {
                    throw new IllegalArgumentException("The uploaded prescription could not be verified.");
                }
                out.put("mode", "image");
                out.put("publicId", publicId);
                out.put("resourceType", text(rx.get("resourceType")));
                out.put("format", text(rx.get("format")).toLowerCase(java.util.Locale.ROOT));
                out.put("version", rx.get("version"));
                out.put("signature", tokenText(rx.get("signature")));
                out.put("assetProof", tokenText(rx.get("assetProof")));
                out.put("imageName", fileName == null ? "" : fileName.trim());
            } else {
                // Preserve access to older orders while new uploads move to private assets.
                String url = urlText(rx.get("imageUrl"));
                if (!isAllowedPrescriptionImageUrl(url)) {
                    throw new IllegalArgumentException("The prescription image link is not valid.");
                }
                out.put("mode", "image");
                out.put("imageUrl", url);
                out.put("imageName", fileName == null ? "" : fileName.trim());
            }

        } else if ("contact".equalsIgnoreCase(lensKind)) {

            out.put("mode", "contact");
            out.put("right", cleanContactEye(rx.get("right")));
            out.put("left", cleanContactEye(rx.get("left")));

        } else {

            out.put("mode", "manual");

            out.put(
                    "sameForBothEyes",
                    Boolean.TRUE.equals(rx.get("sameForBothEyes"))
            );

            out.put(
                    "hasCylinder",
                    Boolean.TRUE.equals(rx.get("hasCylinder"))
            );

            out.put(
                    "needsAddition",
                    Boolean.TRUE.equals(rx.get("needsAddition"))
            );

            out.put("right", cleanEye(rx.get("right")));
            out.put("left", cleanEye(rx.get("left")));
        }

        try {
            return PRESCRIPTION_WRITER.writeValueAsString(out);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to save the prescription.",
                    exception
            );
        }
    }

    static void validatePrescriptionPayload(Map<String, Object> prescription) {
        try {
            String serialized = PRESCRIPTION_WRITER.writeValueAsString(prescription);
            if (serialized.length() > 8192) {
                throw new IllegalArgumentException(
                        "Prescription details are too large."
                );
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Prescription details are invalid."
            );
        }

        int[] nodeCount = {0};
        validatePrescriptionValue(prescription, 0, nodeCount);
    }

    private static void validatePrescriptionValue(
            Object value,
            int depth,
            int[] nodeCount
    ) {
        nodeCount[0]++;
        if (nodeCount[0] > 60 || depth > 2) {
            throw new IllegalArgumentException(
                    "Prescription details are too complex."
            );
        }

        if (value == null || value instanceof Boolean || value instanceof Number) {
            return;
        }

        if (value instanceof String text) {
            if (text.length() > 2048) {
                throw new IllegalArgumentException(
                        "A prescription field is too long."
                );
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            if (map.size() > 20) {
                throw new IllegalArgumentException(
                        "Prescription details contain too many fields."
                );
            }

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || key.isBlank()
                        || key.length() > 40) {
                    throw new IllegalArgumentException(
                            "Prescription field name is invalid."
                    );
                }
                validatePrescriptionValue(entry.getValue(), depth + 1, nodeCount);
            }
            return;
        }

        throw new IllegalArgumentException(
                "Prescription details contain an unsupported value."
        );
    }

    private Map<String, String> cleanEye(Object raw) {

        Map<String, String> eye = new LinkedHashMap<>();

        for (String field : EYE_FIELDS) {

            Object value =
                    raw instanceof Map<?, ?> row
                            ? row.get(field)
                            : null;

            eye.put(field, text(value));
        }

        return eye;
    }

    private Map<String, String> cleanContactEye(Object raw) {

        if (!(raw instanceof Map<?, ?> row)) {
            return null;
        }

        Map<String, String> eye = new LinkedHashMap<>();

        for (String field : CONTACT_EYE_FIELDS) {
            eye.put(field, text(row.get(field)));
        }

        return eye;
    }

    public static boolean isAllowedPrescriptionImageUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String path = uri.getRawPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && path != null
                    && path.contains("/nmk-prescriptions/")
                    && !path.contains("\\..");
        } catch (URISyntaxException invalidUri) {
            return false;
        }
    }

    private String tokenText(Object value) {
        if (value == null) return "";
        String result = String.valueOf(value).trim();
        return result.length() > 64 ? "" : result;
    }

    private String urlText(Object value) {

        if (value == null) {
            return "";
        }

        String result = String.valueOf(value).trim();

        return result.length() > 2048 ? "" : result;
    }

    private String text(Object value) {

        if (value == null) {
            return "";
        }

        String result = String.valueOf(value).trim();

        return result.length() > 40
                ? result.substring(0, 40)
                : result;
    }
}

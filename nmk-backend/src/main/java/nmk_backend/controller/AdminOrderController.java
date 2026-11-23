
package nmk_backend.controller;
import nmk_backend.dto.AdminOrderItemResponse;
import nmk_backend.service.OrderService;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.security.core.Authentication;

import nmk_backend.dto.UpdateDeliveryDetailsRequest;
import nmk_backend.dto.CreateRazorpayRefundRequest;
import nmk_backend.service.RazorpayRefundRequestService;
import nmk_backend.service.RazorpayRefundInitiationService;
import nmk_backend.service.SmsService;
import nmk_backend.dto.AdminOrderResponse;
import nmk_backend.dto.AdminCustomerSummaryResponse;
import nmk_backend.dto.UpdateOrderStatusRequest;
import nmk_backend.entity.Order;
import nmk_backend.entity.OrderItem;
import nmk_backend.service.PrescriptionCloudinaryService;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.OrderRefundRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderController.class);

    private final AdminActionAuthorizationService authorizationService;
    private final SmsService smsService;

    private final OrderRepository orderRepository;
    private final OrderRefundRepository orderRefundRepository;
    private final OrderService orderService;
    private final PrescriptionCloudinaryService prescriptionStorage;
    private final RazorpayRefundRequestService refundRequestService;
    private final RazorpayRefundInitiationService refundInitiationService;
    private final String publicUrl;

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "PENDING",
            "CONFIRMED",
            "SHIPPED",
            "OUT_FOR_DELIVERY",
            "DELIVERED",
            "CANCELLED"
    );

    public AdminOrderController(
            OrderRepository orderRepository,
            OrderRefundRepository orderRefundRepository,
            OrderService orderService,
            SmsService smsService,
            AdminActionAuthorizationService authorizationService,
            PrescriptionCloudinaryService prescriptionStorage,
            RazorpayRefundRequestService refundRequestService,
            RazorpayRefundInitiationService refundInitiationService,
            @Value("${app.public-url:http://localhost:5173}") String publicUrl
    ) {
        this.orderRepository = orderRepository;
        this.orderRefundRepository = orderRefundRepository;
        this.orderService = orderService;
        this.prescriptionStorage = prescriptionStorage;
        this.refundRequestService = refundRequestService;
        this.refundInitiationService = refundInitiationService;
        this.smsService = smsService;
        this.authorizationService = authorizationService;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // Get customer orders in bounded pages. Page IDs first, then fetch item graphs
    // for only those IDs to avoid collection-fetch pagination in Hibernate.
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Page must be non-negative and size must be between 1 and 100"
            ));
        }

        Page<Long> idPage = orderRepository.findAllOrderIds(PageRequest.of(
                page, size,
                Sort.by(Sort.Order.desc("orderDate"), Sort.Order.desc("id"))
        ));
        return ResponseEntity.ok(toOrderResponsePage(idPage));
    }

    @GetMapping("/customers/history")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getCustomerOrderHistory(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Page must be non-negative and size must be between 1 and 100"
            ));
        }
        String normalizedEmail = email == null || email.isBlank()
                ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedPhone = phone == null || phone.isBlank() ? null : phone.trim();
        if (normalizedEmail == null && normalizedPhone == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "A customer email or phone is required"));
        }
        if (normalizedEmail != null) normalizedPhone = null;

        Page<Long> idPage = orderRepository.findCustomerOrderIds(
                normalizedEmail, normalizedPhone,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("orderDate"), Sort.Order.desc("id")))
        );
        return ResponseEntity.ok(toOrderResponsePage(idPage));
    }

    private Page<AdminOrderResponse> toOrderResponsePage(Page<Long> idPage) {
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), idPage.getPageable(), idPage.getTotalElements());
        }
        Map<Long, Order> ordersById = orderRepository.findWithItemsByIdIn(idPage.getContent())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Order::getId, order -> order));
        List<AdminOrderResponse> content = idPage.getContent().stream()
                .map(ordersById::get)
                .filter(java.util.Objects::nonNull)
                .map(this::convertToResponse)
                .toList();
        return new PageImpl<>(content, idPage.getPageable(), idPage.getTotalElements());
    }

    // Get customer summaries without loading every order and item into memory.
    @GetMapping("/customers")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAllCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Page must be non-negative and size must be between 1 and 100"
            ));
        }

        Page<AdminCustomerSummaryResponse> customers = orderRepository
                .findCustomerSummaries(PageRequest.of(page, size))
                .map(AdminCustomerSummaryResponse::from);
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/{orderId}/items/{itemId}/prescription")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getPrescriptionAccess(
            @PathVariable Long orderId,
            @PathVariable Long itemId
    ) {
        if (orderId == null || orderId <= 0 || itemId == null || itemId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid order or item."));
        }
        Order order = orderRepository.findWithItemsByIdIn(List.of(orderId))
                .stream().filter(candidate -> orderId.equals(candidate.getId()))
                .findFirst().orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        OrderItem item = order.getItems().stream()
                .filter(candidate -> itemId.equals(candidate.getId()))
                .findFirst().orElse(null);
        if (item == null || item.getPrescriptionJson() == null || item.getPrescriptionJson().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Map<String, Object> prescription = new ObjectMapper().readValue(
                    item.getPrescriptionJson(), new TypeReference<Map<String, Object>>() {});
            if (!"image".equalsIgnoreCase(String.valueOf(prescription.get("mode")))) {
                return ResponseEntity.badRequest().body(Map.of("message", "This item has no uploaded prescription."));
            }
            String publicId = String.valueOf(prescription.getOrDefault("publicId", ""));
            if (!publicId.isBlank()) {
                String url = prescriptionStorage.createPrivateDownloadUrl(prescription);
                return ResponseEntity.ok(Map.of("url", url, "expiresInSeconds", 300));
            }
            String legacyUrl = String.valueOf(prescription.getOrDefault("imageUrl", ""));
            if (!OrderService.isAllowedPrescriptionImageUrl(legacyUrl)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("url", legacyUrl, "legacyPublicAsset", true));
        } catch (Exception exception) {
            log.warn("Prescription access could not be prepared; orderId={}, itemId={}, exceptionType={}",
                    orderId, itemId, exception.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(Map.of("message", "Unable to open this prescription."));
        }
    }

    @GetMapping("/{orderId}/refunds")
    public ResponseEntity<?> getOrderRefunds(@PathVariable Long orderId) {
        try {
            return ResponseEntity.ok(refundRequestService.listForOrder(orderId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{orderId}/refunds")
    public ResponseEntity<?> initiateOrderRefund(
            @PathVariable Long orderId,
            @Valid @RequestBody CreateRazorpayRefundRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return ResponseEntity.status(401).body(Map.of("message", "Admin login required"));
        }
        try {
            authorizationService.consumeAuthorization(
                    authentication.getName(), authorizationToken, "RAZORPAY_REFUND");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        }

        try {
            var result = refundInitiationService.initiate(orderId, request, authentication.getName());
            return ResponseEntity.status(result.retryWithSameKey()
                    ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(result);
        } catch (RazorpayRefundInitiationService.RefundProviderRejectedException exception) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", exception.getMessage()));
        } catch (nmk_backend.exception.PaymentConfigurationException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Razorpay refund credentials are not configured."));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", exception.getMessage()));
        }
    }

    @DeleteMapping("/clear")
    @Transactional
    public ResponseEntity<?> clearAllOrders(
            @RequestHeader(
                    value = "X-Admin-Action-Authorization",
                    required = false
            ) String authorizationToken,
            Authentication authentication
    ) {
        try {
            if (authentication == null) {
                return ResponseEntity.status(401).body(
                        Map.of("message", "Admin login required")
                );
            }

            authorizationService.consumeAuthorization(
                    authentication.getName(),
                    authorizationToken,
                    "CLEAR_ORDERS"
            );

            if (orderRefundRepository.count() > 0) {
                return ResponseEntity.status(409).body(Map.of(
                        "message", "Orders with provider refund records cannot be cleared; financial refund history must be retained."
                ));
            }

            long deletedCount = orderRepository.count();

            orderRepository.deleteAll();

            return ResponseEntity.ok(Map.of(
                    "message", "All orders deleted successfully",
                    "deletedCount", deletedCount
            ));

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(
                    Map.of("message", exception.getMessage())
            );
        }
    }



    // Update order status with payment and transition validation
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {

        if (request.status() == null || request.status().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Status is required"));
        }

        String newStatus = request.status().trim().toUpperCase();

        if (!ALLOWED_STATUSES.contains(newStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid order status"));
        }

        return orderRepository.findById(id)
                .map(order -> {

                    String currentStatus = order.getOrderStatus();

                    // Only Razorpay verification can confirm payment
                    if ("CONFIRMED".equals(newStatus)) {
                        return ResponseEntity.badRequest()
                                .body(Map.of(
                                        "message",
                                        "Orders can only be confirmed through verified payment"
                                ));
                    }

                    // Do not ship while a provider refund is awaiting its final webhook state.
                    if (Set.of("SHIPPED", "OUT_FOR_DELIVERY", "DELIVERED").contains(newStatus)
                            && "PENDING".equalsIgnoreCase(order.getRefundStatus())) {
                        return ResponseEntity.status(409).body(Map.of(
                                "message", "A provider refund is pending. Wait for Razorpay refund reconciliation before shipping."
                        ));
                    }

                    // Delivery cannot proceed without captured payment
                    if (Set.of(
                            "SHIPPED",
                            "OUT_FOR_DELIVERY",
                            "DELIVERED"
                    ).contains(newStatus)
                            && !"PAID".equalsIgnoreCase(order.getPaymentStatus())) {

                        return ResponseEntity.badRequest()
                                .body(Map.of(
                                        "message",
                                        "Payment must be verified before delivery"
                                ));
                    }

                    // Only unpaid pending orders can be cancelled through this endpoint.
// OrderService handles stock restoration safely.
                    if ("PENDING".equals(currentStatus)
                            && "CANCELLED".equals(newStatus)) {

                        try {
                            orderService.cancelPendingOrder(order.getId());

                            return ResponseEntity.ok(Map.of(
                                    "message", "Pending order cancelled and reserved stock released",
                                    "orderId", order.getId(),
                                    "orderStatus", "CANCELLED"
                            ));

                        } catch (IllegalArgumentException e) {
                            return ResponseEntity.notFound().build();

                        } catch (IllegalStateException e) {
                            return ResponseEntity.badRequest().body(
                                    Map.of("message", e.getMessage())
                            );
                        }
                    }

// Paid orders must not be cancelled without a refund process.
                    if ("CONFIRMED".equals(currentStatus)
                            && "CANCELLED".equals(newStatus)) {

                        String message = "REFUNDED".equalsIgnoreCase(order.getRefundStatus())
                                ? "Provider refund is confirmed. Reconcile stock through an audited inventory adjustment before cancelling the order."
                                : "Paid orders cannot be cancelled through this status action. Initiate the refund with Razorpay; signed provider webhooks update the refund ledger.";
                        return ResponseEntity.badRequest().body(Map.of("message", message));
                    }

                    boolean validTransition = switch (currentStatus) {
                        case "CONFIRMED" ->
                                "SHIPPED".equals(newStatus);

                        case "SHIPPED" ->
                                "OUT_FOR_DELIVERY".equals(newStatus);

                        case "OUT_FOR_DELIVERY" ->
                                "DELIVERED".equals(newStatus);

                        default -> false;
                    };

                    if (!validTransition) {
                        return ResponseEntity.badRequest()
                                .body(Map.of(
                                        "message",
                                        "Invalid status transition: "
                                                + currentStatus + " to " + newStatus
                                ));
                    }

                    if ("SHIPPED".equals(newStatus)
                            && (order.getCourierName() == null
                            || order.getCourierName().isBlank()
                            || order.getTrackingNumber() == null
                            || order.getTrackingNumber().isBlank())) {

                        return ResponseEntity.badRequest()
                                .body(Map.of(
                                        "message",
                                        "Add courier name and tracking number before shipping"
                                ));
                    }

                    order.setOrderStatus(newStatus);
                    // Generate tracking token for older orders
                    if (order.getTrackingToken() == null ||
                            order.getTrackingToken().isBlank()) {

                        order.setTrackingToken(
                                java.util.UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                        );
                    }
                    orderRepository.save(order);
                    if ("SHIPPED".equals(newStatus)
                            || "OUT_FOR_DELIVERY".equals(newStatus)
                            || "DELIVERED".equals(newStatus)) {

                        try {
                            smsService.sendOrderNotification(
                                    order.getPhone(),
                                    String.valueOf(order.getId()),
                                    newStatus,
                                    order.getCourierName() == null ? "" : order.getCourierName(),
                                    order.getTrackingNumber() == null ? "" : order.getTrackingNumber(),
                                    publicUrl + "/track/" + order.getTrackingToken()
                            );
                        } catch (Exception e) {
                            log.warn("SMS notification failed; orderId={}, exceptionType={}",
                                    order.getId(), e.getClass().getSimpleName());
                        }
                    }

                    return ResponseEntity.ok(Map.of(
                            "message", "Order status updated successfully",
                            "orderId", order.getId(),
                            "orderStatus", order.getOrderStatus()
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    // Update courier and tracking information
    @PutMapping("/{id}/delivery")
    public ResponseEntity<?> updateDeliveryDetails(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeliveryDetailsRequest request) {

        if (request.courierName() == null
                || request.courierName().isBlank()
                || request.trackingNumber() == null
                || request.trackingNumber().isBlank()) {

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "Courier name and tracking number are required"
                    ));
        }

        return orderRepository.findById(id)
                .map(order -> {

                    if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())) {
                        return ResponseEntity.badRequest()
                                .body(Map.of(
                                        "message",
                                        "Delivery details cannot be added before payment confirmation"
                                ));
                    }

                    if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
                        return ResponseEntity.status(409).body(Map.of(
                                "message", "Delivery details cannot be changed while a provider refund is pending."
                        ));
                    }

                    if ("CANCELLED".equalsIgnoreCase(order.getOrderStatus())
                            || "DELIVERED".equalsIgnoreCase(order.getOrderStatus())) {
                        return ResponseEntity.badRequest()
                                .body(Map.of(
                                        "message",
                                        "Delivery details cannot be changed for this order"
                                ));
                    }

                    order.setCourierName(request.courierName().trim());
                    order.setTrackingNumber(request.trackingNumber().trim());

                    orderRepository.save(order);

                    return ResponseEntity.ok(Map.of(
                            "message", "Delivery details updated successfully",
                            "orderId", order.getId(),
                            "courierName", order.getCourierName(),
                            "trackingNumber", order.getTrackingNumber()
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    private static final ObjectMapper PRESCRIPTION_READER = new ObjectMapper();

    private Map<String, Object> parsePrescription(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return PRESCRIPTION_READER.readValue(
                    json,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (JsonProcessingException e) {
            log.warn("Unable to parse stored prescription JSON; exceptionType={}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    // Convert entity into response DTO
    private AdminOrderResponse convertToResponse(Order order) {

        List<AdminOrderItemResponse> items = order.getItems()
                .stream()
                .map(item -> new AdminOrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getPrice(),
                        item.getPrescriptionType(),
                        item.getLensType(),
                        item.getPhotochromic(),
                        parsePrescription(item.getPrescriptionJson())
                ))
                .toList();

        return new AdminOrderResponse(
                order.getId(),
                order.getCustomerName(),
                order.getEmail(),
                order.getPhone(),
                order.getAddress(),
                order.getCity(),
                order.getState(),
                order.getPincode(),
                order.getTotalAmount(),
                order.getPaymentStatus(),
                order.getRefundStatus(),
                order.getPaymentCreationStatus(),
                order.getOrderStatus(),
                order.isStockReserved(),
                order.getRazorpayOrderId(),
                order.getRazorpayPaymentId(),
                order.getCourierName(),
                order.getTrackingNumber(),
                order.getTrackingToken(),
                order.getOrderDate(),
                items
        );
    }
}

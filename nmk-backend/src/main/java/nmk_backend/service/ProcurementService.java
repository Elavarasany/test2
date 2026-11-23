package nmk_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ProcurementService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int REPORT_BATCH_SIZE = 200;

    @PersistenceContext
    private EntityManager entityManager;

    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final SupplierReturnRepository supplierReturnRepository;
    private final InventoryLocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final AdminRepository adminRepository;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public ProcurementService(
            SupplierRepository supplierRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderItemRepository purchaseOrderItemRepository,
            GoodsReceiptRepository goodsReceiptRepository,
            SupplierReturnRepository supplierReturnRepository,
            InventoryLocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            AdminRepository adminRepository,
            InventoryService inventoryService,
            ObjectMapper objectMapper
    ) {
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.supplierReturnRepository = supplierReturnRepository;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.adminRepository = adminRepository;
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ProcurementCostReportResponse getProcurementCostReport(
            Long locationId, java.time.LocalDate from, java.time.LocalDate to
    ) {
        validateCostReportRequest(locationId, from, to);
        InventoryLocation location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toExclusive = to.plusDays(1).atStartOfDay();
        BigDecimal receivedCost = ZERO;
        BigDecimal returnedCost = ZERO;
        long receivedQuantity = 0;
        long returnedQuantity = 0;
        Sort reportSort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

        int pageNumber = 0;
        Page<Long> receiptIds;
        do {
            receiptIds = goodsReceiptRepository.findReportIds(
                    locationId, fromDateTime, toExclusive,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, reportSort)
            );
            if (!receiptIds.isEmpty()) {
                List<GoodsReceipt> receipts = goodsReceiptRepository.findAllByIdIn(receiptIds.getContent());
                for (GoodsReceipt receipt : receipts) {
                    for (GoodsReceiptItem item : receipt.getItems()) {
                        BigDecimal total = item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity()))
                                .setScale(2, RoundingMode.HALF_UP);
                        receivedCost = receivedCost.add(total);
                        receivedQuantity += item.getQuantity();
                    }
                }
                entityManager.clear();
            }
        } while (receiptIds.hasNext());

        pageNumber = 0;
        Page<Long> returnIds;
        do {
            returnIds = supplierReturnRepository.findReportIds(
                    locationId, fromDateTime, toExclusive,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, reportSort)
            );
            if (!returnIds.isEmpty()) {
                List<SupplierReturn> returns = supplierReturnRepository.findAllByIdIn(returnIds.getContent());
                for (SupplierReturn record : returns) {
                    for (SupplierReturnItem item : record.getItems()) {
                        BigDecimal total = item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity()))
                                .setScale(2, RoundingMode.HALF_UP);
                        returnedCost = returnedCost.add(total);
                        returnedQuantity += item.getQuantity();
                    }
                }
                entityManager.clear();
            }
        } while (returnIds.hasNext());

        Page<ProcurementCostReportResponse.Entry> firstEntries =
                getProcurementCostReportEntries(locationId, from, to, 0, 50);
        receivedCost = receivedCost.setScale(2, RoundingMode.HALF_UP);
        returnedCost = returnedCost.setScale(2, RoundingMode.HALF_UP);
        return new ProcurementCostReportResponse(location.getId(), location.getName(), from, to,
                receivedQuantity, returnedQuantity, receivedCost, returnedCost,
                receivedCost.subtract(returnedCost).setScale(2, RoundingMode.HALF_UP),
                firstEntries.getContent(), firstEntries.getTotalElements(),
                firstEntries.getNumber(), firstEntries.getSize(), firstEntries.hasNext());
    }

    @Transactional(readOnly = true)
    public Page<ProcurementCostReportResponse.Entry> getProcurementCostReportEntries(
            Long locationId, java.time.LocalDate from, java.time.LocalDate to, int page, int size
    ) {
        validateCostReportRequest(locationId, from, to);
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        if (!locationRepository.existsById(locationId)) {
            throw new InventoryNotFoundException("Inventory location was not found");
        }
        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toExclusive = to.plusDays(1).atStartOfDay();
        return goodsReceiptRepository.findCostReportEntries(
                locationId, fromDateTime, toExclusive, PageRequest.of(page, size)
        ).map(row -> new ProcurementCostReportResponse.Entry(
                row.getType(), row.getReference(), row.getPurchaseOrderId(),
                row.getPurchaseOrderNumber(), row.getSupplierName(), row.getSku(),
                row.getQuantity(), row.getUnitCost(), row.getTotalCost(),
                row.getRecordedBy(), row.getCreatedAt()
        ));
    }

    private void validateCostReportRequest(Long locationId, java.time.LocalDate from, java.time.LocalDate to) {
        if (locationId == null || locationId <= 0 || from == null || to == null) {
            throw new IllegalArgumentException("Location, start date and end date are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("Report date range cannot exceed 367 calendar days");
        }
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> getSuppliers(int page, int size) {
        validateListPage(page, size);
        return supplierRepository.findAllByOrderByNameAsc(PageRequest.of(page, size))
                .map(SupplierResponse::from);
    }

    @Transactional
    public SupplierResponse createSupplier(SupplierCreateRequest request) {
        String name = request.name().trim();
        if (supplierRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("A supplier with this name already exists");
        }
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setContactName(trimToNull(request.contactName()));
        supplier.setPhone(trimToNull(request.phone()));
        supplier.setEmail(trimToNull(request.email()));
        supplier.setAddress(trimToNull(request.address()));
        try {
            // The pre-check gives a friendly response, while the database unique
            // constraint remains the final guard against concurrent duplicate inserts.
            return SupplierResponse.from(supplierRepository.saveAndFlush(supplier));
        } catch (DataIntegrityViolationException exception) {
            if (isSupplierNameUniqueViolation(exception)) {
                throw new IllegalArgumentException("A supplier with this name already exists");
            }
            throw exception;
        }
    }

    private boolean isSupplierNameUniqueViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("uk_suppliers_name")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Transactional
    public SupplierResponse setSupplierActive(Long supplierId, boolean active) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new InventoryNotFoundException("Supplier was not found"));
        supplier.setActive(active);
        return SupplierResponse.from(supplierRepository.saveAndFlush(supplier));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> getPurchaseOrders(int page, int size) {
        validateListPage(page, size);
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Long> ids = purchaseOrderRepository.findHistoryIds(pageable);
        List<PurchaseOrderResponse> content = ids.isEmpty() ? List.of()
                : purchaseOrderRepository.findAllByIdIn(ids.getContent()).stream()
                .sorted(Comparator.comparing(PurchaseOrder::getCreatedAt).reversed()
                        .thenComparing(PurchaseOrder::getId, Comparator.reverseOrder()))
                .map(PurchaseOrderResponse::from).toList();
        return new PageImpl<>(content, pageable, ids.getTotalElements());
    }

    private void validateListPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse getPurchaseOrder(Long purchaseOrderId) {
        return PurchaseOrderResponse.from(purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new InventoryNotFoundException("Purchase order was not found")));
    }

    @Transactional(readOnly = true)
    public Page<GoodsReceiptResponse> getGoodsReceipts(Long purchaseOrderId, int page, int size) {
        validateProcurementHistoryRequest(purchaseOrderId, page, size);
        if (!purchaseOrderRepository.existsById(purchaseOrderId)) {
            throw new InventoryNotFoundException("Purchase order was not found");
        }
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Long> ids = goodsReceiptRepository.findHistoryIds(purchaseOrderId, pageable);
        List<GoodsReceiptResponse> content = ids.isEmpty() ? List.of()
                : goodsReceiptRepository.findAllByIdIn(ids.getContent()).stream()
                .sorted(Comparator.comparing(GoodsReceipt::getCreatedAt).reversed()
                        .thenComparing(GoodsReceipt::getId, Comparator.reverseOrder()))
                .map(GoodsReceiptResponse::from).toList();
        return new PageImpl<>(content, pageable, ids.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<SupplierReturnResponse> getSupplierReturns(Long purchaseOrderId, int page, int size) {
        validateProcurementHistoryRequest(purchaseOrderId, page, size);
        if (!purchaseOrderRepository.existsById(purchaseOrderId)) {
            throw new InventoryNotFoundException("Purchase order was not found");
        }
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Long> ids = supplierReturnRepository.findHistoryIds(purchaseOrderId, pageable);
        List<SupplierReturnResponse> content = ids.isEmpty() ? List.of()
                : supplierReturnRepository.findAllByIdIn(ids.getContent()).stream()
                .sorted(Comparator.comparing(SupplierReturn::getCreatedAt).reversed()
                        .thenComparing(SupplierReturn::getId, Comparator.reverseOrder()))
                .map(SupplierReturnResponse::from).toList();
        return new PageImpl<>(content, pageable, ids.getTotalElements());
    }

    private void validateProcurementHistoryRequest(Long purchaseOrderId, int page, int size) {
        if (purchaseOrderId == null || purchaseOrderId <= 0) {
            throw new IllegalArgumentException("A valid purchase order id is required");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderCreateRequest request, String username) {
        Admin actor = requireActor(username);
        String idempotencyKey = normalizeKey(request.idempotencyKey());
        String requestFingerprint = fingerprint(-1L, request, actor.getUsername());
        var previous = purchaseOrderRepository.findByIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) return replayPurchaseOrder(previous.get(), requestFingerprint);

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .filter(Supplier::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Select an active supplier"));
        InventoryLocation location = locationRepository.findByIdForUpdate(request.locationId())
                .filter(InventoryLocation::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Select an active inventory location"));
        var raced = purchaseOrderRepository.findByIdempotencyKey(idempotencyKey);
        if (raced.isPresent()) return replayPurchaseOrder(raced.get(), requestFingerprint);

        Set<Long> seenVariants = new HashSet<>();
        PurchaseOrder order = new PurchaseOrder();
        order.setSupplier(supplier);
        order.setLocation(location);
        order.setStatus("DRAFT");
        order.setCreatedByUsername(actor.getUsername());
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestFingerprint(requestFingerprint);
        order.setNotes(trimToNull(request.notes()));

        BigDecimal subtotal = ZERO;
        for (PurchaseOrderItemRequest itemRequest : request.items()) {
            if (!seenVariants.add(itemRequest.variantId())) {
                throw new IllegalArgumentException("A product variant can appear only once in a purchase order");
            }
            ProductVariant variant = variantRepository.findById(itemRequest.variantId())
                    .filter(ProductVariant::isActive)
                    .filter(value -> value.getProduct() != null && value.getProduct().isActive())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A purchase order item references an inactive or missing variant"
                    ));
            BigDecimal unitCost = money(itemRequest.unitCost(), "Purchase unit cost");
            BigDecimal lineTotal = unitCost.multiply(BigDecimal.valueOf(itemRequest.orderedQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);

            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrder(order);
            item.setVariant(variant);
            item.setOrderedQuantity(itemRequest.orderedQuantity());
            item.setReceivedQuantity(0);
            item.setReturnedQuantity(0);
            item.setUnitCost(unitCost);
            item.setLineTotal(lineTotal);
            order.getItems().add(item);
            subtotal = subtotal.add(lineTotal);
        }
        order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        return PurchaseOrderResponse.from(purchaseOrderRepository.saveAndFlush(order));
    }

    @Transactional
    public PurchaseOrderResponse submitPurchaseOrder(Long purchaseOrderId) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new InventoryNotFoundException("Purchase order was not found"));
        if (List.of("ORDERED", "PARTIALLY_RECEIVED", "RECEIVED").contains(order.getStatus())) {
            // Safe replay after a client timeout: submission is a one-way state transition.
            return PurchaseOrderResponse.from(order);
        }
        if (!"DRAFT".equals(order.getStatus())) {
            throw new IllegalArgumentException("Only a DRAFT purchase order can be submitted");
        }
        order.setStatus("ORDERED");
        order.setOrderedAt(LocalDateTime.now());
        return PurchaseOrderResponse.from(purchaseOrderRepository.saveAndFlush(order));
    }

    @Transactional
    public GoodsReceiptResponse receiveGoods(
            Long purchaseOrderId,
            GoodsReceiptRequest request,
            String username
    ) {
        Admin actor = requireActor(username);
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint(purchaseOrderId, request, actor.getUsername());
        var previous = goodsReceiptRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) return replayReceipt(previous.get(), fingerprint);

        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new InventoryNotFoundException("Purchase order was not found"));
        var raced = goodsReceiptRepository.findByIdempotencyKey(key);
        if (raced.isPresent()) return replayReceipt(raced.get(), fingerprint);
        if (!List.of("ORDERED", "PARTIALLY_RECEIVED").contains(order.getStatus())) {
            throw new IllegalArgumentException("Only an ORDERED or PARTIALLY_RECEIVED purchase order can receive goods");
        }
        Set<Long> seenItems = new HashSet<>();
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setPurchaseOrder(order);
        receipt.setReceivedByUsername(actor.getUsername());
        receipt.setReason(request.reason().trim());
        receipt.setIdempotencyKey(key);
        receipt.setRequestFingerprint(fingerprint);

        for (GoodsReceiptItemRequest itemRequest : request.items()) {
            if (!seenItems.add(itemRequest.purchaseOrderItemId())) {
                throw new IllegalArgumentException("A purchase order line can appear only once per goods receipt");
            }
            PurchaseOrderItem orderItem = purchaseOrderItemRepository
                    .findByOrderAndIdForUpdate(purchaseOrderId, itemRequest.purchaseOrderItemId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A goods receipt line does not belong to this purchase order"
                    ));
            int outstanding = orderItem.getOrderedQuantity() - orderItem.getReceivedQuantity();
            if (itemRequest.quantity() > outstanding) {
                throw new IllegalArgumentException(
                        "Received quantity exceeds the outstanding ordered quantity for SKU "
                                + orderItem.getVariant().getSku()
                );
            }

            GoodsReceiptItem receiptItem = new GoodsReceiptItem();
            receiptItem.setGoodsReceipt(receipt);
            receiptItem.setPurchaseOrderItem(orderItem);
            receiptItem.setQuantity(itemRequest.quantity());
            receiptItem.setUnitCost(orderItem.getUnitCost());
            receipt.getItems().add(receiptItem);
            orderItem.setReceivedQuantity(orderItem.getReceivedQuantity() + itemRequest.quantity());
        }

        if (receipt.getItems().isEmpty()) {
            throw new IllegalArgumentException("At least one goods receipt line is required");
        }

        goodsReceiptRepository.saveAndFlush(receipt);
        for (GoodsReceiptItem item : receipt.getItems()) {
            inventoryService.receivePurchaseOrderStock(
                    order.getLocation().getId(),
                    item.getPurchaseOrderItem().getVariant().getId(),
                    item.getQuantity(),
                    "GR:" + key + ":" + item.getPurchaseOrderItem().getId(),
                    actor.getId(),
                    request.reason().trim(),
                    receipt.getId()
            );
        }

        boolean fullyReceived = order.getItems().stream()
                .allMatch(item -> item.getReceivedQuantity() >= item.getOrderedQuantity());
        order.setStatus(fullyReceived ? "RECEIVED" : "PARTIALLY_RECEIVED");
        purchaseOrderRepository.saveAndFlush(order);
        return GoodsReceiptResponse.from(receipt);
    }

    @Transactional
    public SupplierReturnResponse returnGoodsToSupplier(
            Long purchaseOrderId,
            SupplierReturnRequest request,
            String username
    ) {
        Admin actor = requireActor(username);
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint(purchaseOrderId, request, actor.getUsername());
        var previous = supplierReturnRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) return replaySupplierReturn(previous.get(), fingerprint);

        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new InventoryNotFoundException("Purchase order was not found"));
        var raced = supplierReturnRepository.findByIdempotencyKey(key);
        if (raced.isPresent()) return replaySupplierReturn(raced.get(), fingerprint);
        if (!List.of("PARTIALLY_RECEIVED", "RECEIVED").contains(order.getStatus())) {
            throw new IllegalArgumentException("Supplier returns require a purchase order with received goods");
        }
        Set<Long> seenItems = new HashSet<>();
        SupplierReturn supplierReturn = new SupplierReturn();
        supplierReturn.setPurchaseOrder(order);
        supplierReturn.setProcessedByUsername(actor.getUsername());
        supplierReturn.setReason(request.reason().trim());
        supplierReturn.setIdempotencyKey(key);
        supplierReturn.setRequestFingerprint(fingerprint);

        for (SupplierReturnItemRequest itemRequest : request.items()) {
            if (!seenItems.add(itemRequest.purchaseOrderItemId())) {
                throw new IllegalArgumentException("A purchase order line can appear only once per supplier return");
            }
            PurchaseOrderItem orderItem = purchaseOrderItemRepository
                    .findByOrderAndIdForUpdate(purchaseOrderId, itemRequest.purchaseOrderItemId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A supplier return line does not belong to this purchase order"
                    ));
            int returnable = orderItem.getReceivedQuantity() - orderItem.getReturnedQuantity();
            if (itemRequest.quantity() > returnable) {
                throw new IllegalArgumentException(
                        "Supplier return quantity exceeds received goods not already returned for SKU "
                                + orderItem.getVariant().getSku()
                );
            }

            SupplierReturnItem returnItem = new SupplierReturnItem();
            returnItem.setSupplierReturn(supplierReturn);
            returnItem.setPurchaseOrderItem(orderItem);
            returnItem.setQuantity(itemRequest.quantity());
            returnItem.setUnitCost(orderItem.getUnitCost());
            supplierReturn.getItems().add(returnItem);
            orderItem.setReturnedQuantity(orderItem.getReturnedQuantity() + itemRequest.quantity());
        }

        supplierReturnRepository.saveAndFlush(supplierReturn);
        for (SupplierReturnItem item : supplierReturn.getItems()) {
            inventoryService.returnStockToSupplier(
                    order.getLocation().getId(),
                    item.getPurchaseOrderItem().getVariant().getId(),
                    item.getQuantity(),
                    "SUP-RET:" + key + ":" + item.getPurchaseOrderItem().getId(),
                    actor.getId(),
                    request.reason().trim(),
                    supplierReturn.getId()
            );
        }

        purchaseOrderRepository.saveAndFlush(order);
        return SupplierReturnResponse.from(supplierReturn);
    }

    private PurchaseOrderResponse replayPurchaseOrder(PurchaseOrder existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new IllegalArgumentException("Purchase order idempotency key was reused with different details");
        }
        return PurchaseOrderResponse.from(existing);
    }

    private GoodsReceiptResponse replayReceipt(GoodsReceipt existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new IllegalArgumentException("Goods receipt idempotency key was reused with different details");
        }
        return GoodsReceiptResponse.from(existing);
    }

    private SupplierReturnResponse replaySupplierReturn(SupplierReturn existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new IllegalArgumentException("Supplier return idempotency key was reused with different details");
        }
        return SupplierReturnResponse.from(existing);
    }

    private Admin requireActor(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated staff account is required");
        }
        return adminRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated staff account was not found"));
    }

    private BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.scale() > 2 || value.precision() > 12 || value.compareTo(ZERO) <= 0) {
            throw new IllegalArgumentException(label + " must be a positive amount with up to two decimal places");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100) {
            throw new IllegalArgumentException("An idempotency key of 1 to 100 characters is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String fingerprint(Long orderId, Object request, String authenticatedUsername) {
        try {
            String actor = authenticatedUsername == null
                    ? null : authenticatedUsername.trim().toLowerCase(Locale.ROOT);
            byte[] json = objectMapper.writeValueAsBytes(Map.of(
                    "purchaseOrderId", orderId,
                    "authenticatedUsername", actor,
                    "request", request
            ));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint procurement request", exception);
        }
    }
}
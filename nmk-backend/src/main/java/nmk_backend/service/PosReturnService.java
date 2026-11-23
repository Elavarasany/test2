package nmk_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class PosReturnService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Set<String> DISPOSITIONS = Set.of("RESTOCK", "DAMAGED", "DISCARD");
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "UPI", "CARD");

    private final InventoryLocationRepository locationRepository;
    private final PosSaleRepository saleRepository;
    private final PosReturnRepository returnRepository;
    private final PosReturnItemRepository returnItemRepository;
    private final AdminRepository adminRepository;
    private final InventoryService inventoryService;
    private final PosShiftService shiftService;
    private final ObjectMapper objectMapper;
    private final AdminLocationAccessService locationAccessService;

    public PosReturnService(
            InventoryLocationRepository locationRepository,
            PosSaleRepository saleRepository,
            PosReturnRepository returnRepository,
            PosReturnItemRepository returnItemRepository,
            AdminRepository adminRepository,
            InventoryService inventoryService,
            PosShiftService shiftService,
            ObjectMapper objectMapper
    ) {
        this(locationRepository, saleRepository, returnRepository, returnItemRepository,
                adminRepository, inventoryService, shiftService, objectMapper, null);
    }

    @Autowired
    public PosReturnService(
            InventoryLocationRepository locationRepository,
            PosSaleRepository saleRepository,
            PosReturnRepository returnRepository,
            PosReturnItemRepository returnItemRepository,
            AdminRepository adminRepository,
            InventoryService inventoryService,
            PosShiftService shiftService,
            ObjectMapper objectMapper,
            AdminLocationAccessService locationAccessService
    ) {
        this.locationRepository = locationRepository;
        this.saleRepository = saleRepository;
        this.returnRepository = returnRepository;
        this.returnItemRepository = returnItemRepository;
        this.adminRepository = adminRepository;
        this.inventoryService = inventoryService;
        this.shiftService = shiftService;
        this.objectMapper = objectMapper;
        this.locationAccessService = locationAccessService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PosReturnResponse processReturn(
            Long saleId,
            PosReturnRequest request,
            String username
    ) {
        return processReturn(saleId, request, username, "CASHIER");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PosReturnResponse processReturn(
            Long saleId,
            PosReturnRequest request,
            String username,
            String role
    ) {
        return processReturnInternal(saleId, request, username, "RETURN", role);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PosReturnResponse processVoid(
            Long saleId,
            PosVoidRequest request,
            String username
    ) {
        return processVoid(saleId, request, username, "CASHIER");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PosReturnResponse processVoid(
            Long saleId,
            PosVoidRequest request,
            String username,
            String role
    ) {
        if (request == null) {
            throw new IllegalArgumentException("A void request is required");
        }
        PosSale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new InventoryNotFoundException("Original POS sale was not found"));
        List<PosReturnItemRequest> fullSaleItems = sale.getItems().stream()
                .map(item -> new PosReturnItemRequest(
                        item.getId(), item.getQuantity(), "RESTOCK", null
                ))
                .toList();
        List<PosRefundPaymentRequest> refundPayments = request.refundPayments() == null
                ? List.of() : request.refundPayments();
        PosReturnRequest fullReturn = new PosReturnRequest(
                request.shiftId(), request.reason(), request.idempotencyKey(),
                fullSaleItems, refundPayments
        );
        return processReturnInternal(saleId, fullReturn, username, "VOID", role);
    }

    private PosReturnResponse processReturnInternal(
            Long saleId,
            PosReturnRequest request,
            String username,
            String returnType,
            String role
    ) {
        validateRequest(saleId, request, username);
        PosSale saleSnapshot = saleRepository.findById(saleId)
                .orElseThrow(() -> new InventoryNotFoundException("Original POS sale was not found"));
        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, saleSnapshot.getLocationId());
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint(saleId, request, returnType, username);

        var previous = returnRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replay(previous.get(), fingerprint);
        }

        // Global lock order for POS mutations is location -> shift -> sale -> inventory rows.
        // Checkout already uses location -> shift. Matching that order here prevents a
        // checkout/return deadlock when both operations touch the same store and drawer.
        locationRepository.findByIdForUpdate(saleSnapshot.getLocationId())
                .orElseThrow(() -> new InventoryNotFoundException("Original sale inventory location was not found"));

        PosShift shift = shiftService.requireOpenShiftForManagerAction(
                request.shiftId(), saleSnapshot.getLocationId(), username, role
        );

        PosSale sale = saleRepository.findByIdForUpdate(saleId)
                .orElseThrow(() -> new InventoryNotFoundException("Original POS sale was not found"));

        // Recheck after acquiring the sale lock. All returns for one sale serialize here.
        // MySQL's default REPEATABLE_READ can retain a pre-lock snapshot for the
        // aggregate return-quantity query even after this transaction waits for the
        // sale row lock. READ_COMMITTED ensures the next statement sees the preceding
        // committed return, preventing concurrent requests from returning the same unit.
        var raced = returnRepository.findByIdempotencyKeyForUpdate(key);
        if (raced.isPresent()) {
            return replay(raced.get(), fingerprint);
        }

        if (!"COMPLETED".equals(sale.getStatus())) {
            throw new IllegalArgumentException("Only completed POS sales can be returned or voided");
        }
        if ("VOID".equals(returnType)
                && !returnRepository.findBySaleIdOrderByCreatedAtDesc(saleId).isEmpty()) {
            throw new IllegalArgumentException("A sale cannot be voided after any return has been processed");
        }

        Admin actor = adminRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated staff account was not found"));

        Map<Long, PosSaleItem> originalItems = new HashMap<>();
        sale.getItems().forEach(item -> originalItems.put(item.getId(), item));
        Set<Long> seenSaleItemIds = new HashSet<>();

        PosReturn returnRecord = new PosReturn();
        returnRecord.setSale(sale);
        returnRecord.setShift(shift);
        returnRecord.setProcessedByUsername(username);
        returnRecord.setReason(request.reason().trim());
        returnRecord.setStatus("COMPLETED");
        returnRecord.setReturnType(returnType);
        returnRecord.setIdempotencyKey(key);
        returnRecord.setRequestFingerprint(fingerprint);

        List<PosRefundPaymentRequest> refundRequests = request.refundPayments() == null
                ? List.of() : request.refundPayments();
        BigDecimal refundTotal = ZERO;
        for (PosReturnItemRequest itemRequest : request.items()) {
            if (!seenSaleItemIds.add(itemRequest.saleItemId())) {
                throw new IllegalArgumentException("A sale line can appear only once in a return");
            }

            PosSaleItem saleItem = originalItems.get(itemRequest.saleItemId());
            if (saleItem == null) {
                throw new IllegalArgumentException("A return item does not belong to the selected original sale");
            }

            int soldQuantity = saleItem.getQuantity();
            long alreadyReturned = Optional.ofNullable(
                    returnItemRepository.sumReturnedQuantityBySaleItemId(saleItem.getId())
            ).orElse(0L);
            int remainingQuantity = soldQuantity - Math.toIntExact(alreadyReturned);
            if (itemRequest.quantity() > remainingQuantity) {
                throw new IllegalArgumentException(
                        "Return quantity exceeds the remaining sold quantity for SKU " + saleItem.getSku()
                );
            }

            String disposition = normalizeDisposition(itemRequest.restockDisposition());
            String dispositionReason = itemRequest.dispositionReason() == null
                    ? null : itemRequest.dispositionReason().trim();
            if (!"RESTOCK".equals(disposition)
                    && (dispositionReason == null || dispositionReason.isBlank())) {
                throw new IllegalArgumentException(
                        "A reason is required when returned goods are marked damaged or discarded"
                );
            }

            BigDecimal lineNetTotal = PosReturnPricing.netLineValue(sale, saleItem);
            BigDecimal previouslyRefunded = Optional.ofNullable(
                    returnItemRepository.sumRefundedAmountBySaleItemId(saleItem.getId())
            ).orElse(ZERO);
            BigDecimal lineRefund = PosReturnPricing.refundForQuantity(
                    lineNetTotal, soldQuantity, remainingQuantity,
                    itemRequest.quantity(), previouslyRefunded
            );

            PosReturnItem returnItem = new PosReturnItem();
            returnItem.setReturnRecord(returnRecord);
            returnItem.setSaleItem(saleItem);
            returnItem.setQuantity(itemRequest.quantity());
            returnItem.setRefundAmount(lineRefund);
            returnItem.setUnitRefundAmount(
                    lineRefund.divide(BigDecimal.valueOf(itemRequest.quantity()), 2, RoundingMode.HALF_UP)
            );
            returnItem.setRestockDisposition(disposition);
            returnItem.setDispositionReason(dispositionReason);
            returnRecord.getItems().add(returnItem);
            refundTotal = refundTotal.add(lineRefund);
        }

        refundTotal = refundTotal.setScale(2, RoundingMode.HALF_UP);
        if (refundTotal.compareTo(ZERO) <= 0 && !refundRequests.isEmpty()) {
            throw new IllegalArgumentException("A zero-value return must not include refund payments");
        }
        if (refundTotal.compareTo(ZERO) > 0 && refundRequests.isEmpty()) {
            throw new IllegalArgumentException("At least one refund payment is required");
        }

        BigDecimal refundPaymentTotal = ZERO;
        int paymentIndex = 0;
        for (PosRefundPaymentRequest paymentRequest : refundRequests) {
            String method = normalizePaymentMethod(paymentRequest.paymentMethod());
            BigDecimal paymentAmount = money(paymentRequest.amount(), "Refund payment amount");
            if (paymentAmount.compareTo(ZERO) <= 0) {
                throw new IllegalArgumentException("Refund payment amounts must be greater than zero");
            }
            String reference = paymentRequest.transactionReference() == null
                    ? null : paymentRequest.transactionReference().trim();
            if (!"CASH".equals(method) && (reference == null || reference.isBlank())) {
                throw new IllegalArgumentException("A transaction reference is required for UPI and card refunds");
            }
            if (reference != null && reference.length() > 150) {
                throw new IllegalArgumentException("Refund transaction reference cannot exceed 150 characters");
            }

            PosRefundPayment refundPayment = new PosRefundPayment();
            refundPayment.setReturnRecord(returnRecord);
            refundPayment.setPaymentMethod(method);
            refundPayment.setAmount(paymentAmount);
            refundPayment.setTransactionReference(reference);
            returnRecord.getRefundPayments().add(refundPayment);
            refundPaymentTotal = refundPaymentTotal.add(paymentAmount);
            paymentIndex++;
        }

        if (refundPaymentTotal.setScale(2, RoundingMode.HALF_UP).compareTo(refundTotal) != 0) {
            throw new IllegalArgumentException("Refund payment allocations must equal the calculated refund total exactly");
        }

        BigDecimal previousSaleRefunds = Optional.ofNullable(
                returnRepository.sumRefundTotalBySaleId(sale.getId())
        ).orElse(ZERO);
        if (previousSaleRefunds.add(refundTotal).compareTo(sale.getGrandTotal()) > 0) {
            throw new IllegalArgumentException("Cumulative refunds cannot exceed the original sale total");
        }

        returnRecord.setRefundTotal(refundTotal);
        returnRepository.saveAndFlush(returnRecord);

        for (PosReturnItem returnItem : returnRecord.getItems()) {
            if ("RESTOCK".equals(returnItem.getRestockDisposition())) {
                inventoryService.restockCustomerReturn(
                        sale.getLocationId(),
                        returnItem.getSaleItem().getVariantId(),
                        returnItem.getQuantity(),
                        "POS-RETURN-STOCK:" + key + ":" + returnItem.getSaleItem().getId(),
                        actor.getId(),
                        "Resaleable customer return " + returnRecord.getReturnNumber(),
                        returnRecord.getId()
                );
            }
        }

        paymentIndex = 0;
        for (PosRefundPayment refundPayment : returnRecord.getRefundPayments()) {
            if ("CASH".equals(refundPayment.getPaymentMethod())) {
                shiftService.recordCashRefund(
                        shift,
                        refundPayment.getAmount(),
                        "POS-RETURN-CASH:" + key + ":" + paymentIndex,
                        username,
                        returnRecord.getId()
                );
            }
            paymentIndex++;
        }

        return PosReturnResponse.from(returnRecord);
    }

    @Transactional(readOnly = true)
    public List<PosReturnResponse> getReturns(Long saleId) {
        return getReturns(saleId, null, null);
    }

    @Transactional(readOnly = true)
    public List<PosReturnResponse> getReturns(Long saleId, String username, String role) {
        if (saleId == null || saleId <= 0) {
            throw new IllegalArgumentException("A valid sale id is required");
        }
        PosSale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new InventoryNotFoundException("Original POS sale was not found"));
        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, sale.getLocationId());
        return returnRepository.findBySaleIdOrderByCreatedAtDesc(saleId)
                .stream().map(PosReturnResponse::from).toList();
    }

    private PosReturnResponse replay(PosReturn existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new IllegalArgumentException("This return idempotency key was already used with different details");
        }
        return PosReturnResponse.from(existing);
    }

    private void validateRequest(Long saleId, PosReturnRequest request, String username) {
        if (saleId == null || saleId <= 0 || request == null) {
            throw new IllegalArgumentException("A valid original sale and return request are required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated manager is required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("At least one original sale line is required");
        }
        if (request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > 500) {
            throw new IllegalArgumentException("A return reason of 1 to 500 characters is required");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("A return idempotency key is required");
        }
        if (request.items().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Return item rows cannot be null");
        }
        if (request.refundPayments() == null) {
            throw new IllegalArgumentException("Refund payment list is required (it may be empty for a zero-value return)");
        }
        if (request.refundPayments().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Refund payment rows cannot be null");
        }
    }

    private String normalizeDisposition(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!DISPOSITIONS.contains(normalized)) {
            throw new IllegalArgumentException("Restock disposition must be RESTOCK, DAMAGED or DISCARD");
        }
        return normalized;
    }

    private String normalizePaymentMethod(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PAYMENT_METHODS.contains(normalized)) {
            throw new IllegalArgumentException("Refund payment method must be CASH, UPI or CARD");
        }
        return normalized;
    }

    private BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.scale() > 2 || value.precision() > 12) {
            throw new IllegalArgumentException(label + " must be a valid amount with up to two decimal places");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 100) {
            throw new IllegalArgumentException("A return idempotency key of 1 to 100 characters is required");
        }
        return key.trim();
    }

    private String fingerprint(Long saleId, PosReturnRequest request, String returnType, String username) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(
                    Map.of("saleId", saleId, "returnType", returnType, "username", username.trim().toLowerCase(Locale.ROOT), "request", request)
            );
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint return request", exception);
        }
    }
}
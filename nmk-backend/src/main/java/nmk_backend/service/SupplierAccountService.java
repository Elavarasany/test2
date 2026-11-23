package nmk_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SupplierAccountService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final SupplierRepository supplierRepository;
    private final SupplierBillRepository billRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final SupplierBillCreditNoteRepository creditNoteRepository;
    private final SupplierReturnRepository supplierReturnRepository;
    private final SupplierBillLandedCostAllocationRepository allocationRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final AdminRepository adminRepository;
    private final PosShiftService posShiftService;
    private final ObjectMapper objectMapper;

    public SupplierAccountService(
            SupplierRepository supplierRepository,
            SupplierBillRepository billRepository,
            SupplierPaymentRepository paymentRepository,
            SupplierBillCreditNoteRepository creditNoteRepository,
            SupplierReturnRepository supplierReturnRepository,
            SupplierBillLandedCostAllocationRepository allocationRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            GoodsReceiptRepository goodsReceiptRepository,
            AdminRepository adminRepository,
            PosShiftService posShiftService,
            ObjectMapper objectMapper
    ) {
        this.supplierRepository = supplierRepository;
        this.billRepository = billRepository;
        this.paymentRepository = paymentRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.supplierReturnRepository = supplierReturnRepository;
        this.allocationRepository = allocationRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.adminRepository = adminRepository;
        this.posShiftService = posShiftService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SupplierBillResponse createBill(SupplierBillCreateRequest request, String username) {
        Admin actor = requireActor(username);
        validateBillRequest(request);
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint("BILL", null, request, actor.getUsername());

        var previous = billRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) return replayBill(previous.get(), fingerprint);

        Supplier supplier = supplierRepository.findByIdForUpdate(request.supplierId())
                .orElseThrow(() -> new InventoryNotFoundException("Supplier was not found"));
        if (!supplier.isActive()) throw new IllegalArgumentException("An inactive supplier cannot receive a new bill");
        var raced = billRepository.findByIdempotencyKey(key);
        if (raced.isPresent()) return replayBill(raced.get(), fingerprint);

        String billType = normalizeBillType(request.billType());
        PurchaseOrder order = null;
        List<GoodsReceiptItem> receiptItems = List.of();
        if (request.purchaseOrderId() != null) {
            order = purchaseOrderRepository.findByIdForUpdate(request.purchaseOrderId())
                    .orElseThrow(() -> new InventoryNotFoundException("Purchase order was not found"));
            if (!order.getSupplier().getId().equals(supplier.getId())) {
                throw new IllegalArgumentException("Purchase order belongs to a different supplier");
            }
        }
        if ("GOODS".equals(billType) || "LANDED_COST".equals(billType)) {
            if (order == null) throw new IllegalArgumentException("A purchase order is required for goods and landed-cost bills");
            if (!List.of("PARTIALLY_RECEIVED", "RECEIVED").contains(order.getStatus())) {
                throw new IllegalArgumentException("Goods and landed-cost bills require goods already received against the purchase order");
            }
            receiptItems = goodsReceiptRepository.findByPurchaseOrderIdOrderByCreatedAtDesc(order.getId())
                    .stream().flatMap(receipt -> receipt.getItems().stream())
                    .filter(item -> item.getQuantity() != null && item.getQuantity() > 0
                            && item.getUnitCost() != null && item.getUnitCost().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(java.util.Comparator.comparing(GoodsReceiptItem::getId))
                    .toList();
            if (receiptItems.isEmpty()) {
                throw new IllegalArgumentException("The purchase order has no accepted goods receipt lines");
            }
        }
        if (billRepository.existsBySupplierIdAndInvoiceNumberIgnoreCase(supplier.getId(), request.invoiceNumber().trim())) {
            throw new IllegalArgumentException("This supplier invoice number has already been recorded");
        }

        SupplierBill bill = new SupplierBill();
        bill.setSupplier(supplier);
        bill.setPurchaseOrder(order);
        bill.setInvoiceNumber(request.invoiceNumber().trim());
        bill.setInvoiceDate(request.invoiceDate());
        bill.setDueDate(request.dueDate());
        bill.setAmount(money(request.amount(), "Supplier bill amount"));
        bill.setBillType(billType);
        bill.setStatus("POSTED");
        bill.setCreatedByUsername(actor.getUsername());
        bill.setIdempotencyKey(key);
        bill.setRequestFingerprint(fingerprint);
        billRepository.saveAndFlush(bill);

        if ("LANDED_COST".equals(billType)) {
            allocateLandedCost(bill, receiptItems);
        }
        return toBillResponse(bill);
    }

    @Transactional
    public SupplierPaymentResponse recordPayment(
            Long billId, SupplierPaymentRequest request, String username, String role
    ) {
        Admin actor = requireActor(username);
        validatePaymentRequest(request);
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint("PAYMENT", billId, request, actor.getUsername());

        var previous = paymentRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) return replayPayment(previous.get(), fingerprint);

        SupplierBill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new InventoryNotFoundException("Supplier bill was not found"));
        var raced = paymentRepository.findByIdempotencyKey(key);
        if (raced.isPresent()) return replayPayment(raced.get(), fingerprint);
        if (!"POSTED".equals(bill.getStatus())) {
            throw new IllegalArgumentException("Only a posted supplier bill can receive a payment");
        }

        BigDecimal amount = money(request.amount(), "Supplier payment amount");
        String method = normalizePaymentMethod(request.paymentMethod());
        String reference = trimToNull(request.reference());
        Long shiftId = request.shiftId();
        if ("CASH".equals(method)) {
            if (shiftId == null) throw new IllegalArgumentException("A POS shift is required for a supplier cash payment");
            if (bill.getPurchaseOrder() == null) {
                throw new IllegalArgumentException("A purchase-order location is required for a supplier cash payment");
            }
        } else {
            if (shiftId != null) throw new IllegalArgumentException("Only cash supplier payments can use a POS shift");
            if (reference == null || reference.length() > 160) {
                throw new IllegalArgumentException("A bank/UPI/payment reference is required");
            }
        }

        // Use the locked bill row as the current-read payable balance. A SUM query here
        // would use an older REPEATABLE READ snapshot when concurrent payments waited on this bill.
        BigDecimal paid = bill.getPaidAmount() == null ? ZERO : bill.getPaidAmount();
        BigDecimal credited = bill.getCreditedAmount() == null ? ZERO : bill.getCreditedAmount();
        BigDecimal outstanding = bill.getAmount().subtract(paid).subtract(credited).setScale(2);
        if (amount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException("Supplier payment exceeds the outstanding bill balance of " + outstanding);
        }

        SupplierPayment payment = new SupplierPayment();
        payment.setBill(bill);
        payment.setSupplier(bill.getSupplier());
        payment.setPaymentMethod(method);
        payment.setAmount(amount);
        payment.setReference(reference);
        payment.setShiftId(shiftId);
        payment.setStatus("CONFIRMED");
        payment.setCreatedByUsername(actor.getUsername());
        payment.setIdempotencyKey(key);
        payment.setRequestFingerprint(fingerprint);
        paymentRepository.saveAndFlush(payment);
        bill.setPaidAmount(paid.add(amount).setScale(2));
        billRepository.saveAndFlush(bill);

        if ("CASH".equals(method)) {
            posShiftService.recordSupplierPayment(
                    shiftId, amount, key, actor.getUsername(), role, payment.getId(),
                    bill.getPurchaseOrder().getLocation().getId()
            );
        }
        return toPaymentResponse(payment);
    }

    @Transactional
    public SupplierCreditNoteResponse recordCreditNote(
            Long billId, SupplierCreditNoteRequest request, String username
    ) {
        Admin actor = requireActor(username);
        validateCreditNoteRequest(request);
        String key = normalizeKey(request.idempotencyKey());
        String fingerprint = fingerprint("CREDIT_NOTE", billId, request, actor.getUsername());

        var previous = creditNoteRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) return replayCreditNote(previous.get(), fingerprint);

        SupplierBill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new InventoryNotFoundException("Supplier bill was not found"));
        var raced = creditNoteRepository.findByIdempotencyKey(key);
        if (raced.isPresent()) return replayCreditNote(raced.get(), fingerprint);
        if (!"POSTED".equals(bill.getStatus())) {
            throw new IllegalArgumentException("Only a posted supplier bill can receive a credit note");
        }
        if (creditNoteRepository.existsBySupplierIdAndCreditNoteNumberIgnoreCase(
                bill.getSupplier().getId(), request.creditNoteNumber().trim())) {
            throw new IllegalArgumentException("This supplier credit note number has already been recorded");
        }

        SupplierReturn supplierReturn = null;
        if (request.supplierReturnId() != null) {
            supplierReturn = supplierReturnRepository.findById(request.supplierReturnId())
                    .orElseThrow(() -> new InventoryNotFoundException("Supplier return was not found"));
            if (!supplierReturn.getPurchaseOrder().getSupplier().getId().equals(bill.getSupplier().getId())) {
                throw new IllegalArgumentException("Supplier return belongs to a different supplier");
            }
            if (bill.getPurchaseOrder() == null
                    || !bill.getPurchaseOrder().getId().equals(supplierReturn.getPurchaseOrder().getId())) {
                throw new IllegalArgumentException("Supplier return must belong to the purchase order linked to this bill");
            }
        }

        BigDecimal paid = bill.getPaidAmount() == null ? ZERO : bill.getPaidAmount();
        BigDecimal credited = bill.getCreditedAmount() == null ? ZERO : bill.getCreditedAmount();
        BigDecimal outstanding = bill.getAmount().subtract(paid).subtract(credited).setScale(2);
        BigDecimal amount = money(request.amount(), "Supplier credit note amount");
        if (amount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException("Credit note exceeds the outstanding bill balance of " + outstanding
                    + "; supplier credit balances on already-paid bills are not supported");
        }

        SupplierBillCreditNote creditNote = new SupplierBillCreditNote();
        creditNote.setBill(bill);
        creditNote.setSupplier(bill.getSupplier());
        creditNote.setSupplierReturn(supplierReturn);
        creditNote.setCreditNoteNumber(request.creditNoteNumber().trim());
        creditNote.setIssueDate(request.issueDate());
        creditNote.setAmount(amount);
        creditNote.setReason(request.reason().trim());
        creditNote.setStatus("CONFIRMED");
        creditNote.setCreatedByUsername(actor.getUsername());
        creditNote.setIdempotencyKey(key);
        creditNote.setRequestFingerprint(fingerprint);
        creditNoteRepository.saveAndFlush(creditNote);

        bill.setCreditedAmount(credited.add(amount).setScale(2));
        billRepository.saveAndFlush(bill);
        return toCreditNoteResponse(creditNote);
    }

    @Transactional(readOnly = true)
    public List<SupplierCreditNoteResponse> getCreditNotes(Long billId, String username) {
        requireActor(username);
        if (billId == null || billId <= 0 || !billRepository.existsById(billId)) {
            throw new InventoryNotFoundException("Supplier bill was not found");
        }
        return creditNoteRepository.findByBillIdOrderByIssueDateDescIdDesc(billId)
                .stream().map(this::toCreditNoteResponse).toList();
    }

    @Transactional(readOnly = true)
    public SupplierPayablesAgingResponse getPayablesAging(Long supplierId, LocalDate asOfDate, String username) {
        requireActor(username);
        if (supplierId == null || supplierId <= 0 || !supplierRepository.existsById(supplierId)) {
            throw new InventoryNotFoundException("Supplier was not found");
        }
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now(ZoneId.of("Asia/Kolkata")) : asOfDate;
        BigDecimal notDue = ZERO;
        BigDecimal overdue1To30 = ZERO;
        BigDecimal overdue31To60 = ZERO;
        BigDecimal overdue61To90 = ZERO;
        BigDecimal overdueOver90 = ZERO;
        int openBills = 0;
        int overdueBills = 0;

        Page<SupplierBill> page = billRepository.findBySupplierIdOrderByInvoiceDateDescCreatedAtDesc(
                supplierId, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "id"))
        );
        while (true) {
            for (SupplierBill bill : page.getContent()) {
                if (!"POSTED".equals(bill.getStatus())) continue;
                BigDecimal paid = paymentRepository.sumConfirmedAmountByBillId(bill.getId());
                if (paid == null) paid = ZERO;
                BigDecimal credited = bill.getCreditedAmount() == null ? ZERO : bill.getCreditedAmount();
                BigDecimal outstanding = bill.getAmount().subtract(paid).subtract(credited).setScale(2);
                if (outstanding.compareTo(BigDecimal.ZERO) <= 0) continue;
                openBills++;

                LocalDate dueDate = bill.getDueDate();
                if (dueDate == null || !dueDate.isBefore(effectiveDate)) {
                    notDue = notDue.add(outstanding);
                    continue;
                }
                overdueBills++;
                long daysOverdue = ChronoUnit.DAYS.between(dueDate, effectiveDate);
                if (daysOverdue <= 30) overdue1To30 = overdue1To30.add(outstanding);
                else if (daysOverdue <= 60) overdue31To60 = overdue31To60.add(outstanding);
                else if (daysOverdue <= 90) overdue61To90 = overdue61To90.add(outstanding);
                else overdueOver90 = overdueOver90.add(outstanding);
            }
            if (!page.hasNext()) break;
            page = billRepository.findBySupplierIdOrderByInvoiceDateDescCreatedAtDesc(
                    supplierId, page.nextPageable()
            );
        }

        BigDecimal total = notDue.add(overdue1To30).add(overdue31To60)
                .add(overdue61To90).add(overdueOver90).setScale(2);
        return new SupplierPayablesAgingResponse(
                supplierId, effectiveDate, openBills, overdueBills,
                notDue.setScale(2), overdue1To30.setScale(2), overdue31To60.setScale(2),
                overdue61To90.setScale(2), overdueOver90.setScale(2), total
        );
    }

    @Transactional(readOnly = true)
    public Page<SupplierBillResponse> getBills(Long supplierId, int page, int size, String username) {
        requireActor(username);
        if (supplierId == null || supplierId <= 0 || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Valid supplier, page and size are required");
        }
        if (!supplierRepository.existsById(supplierId)) {
            throw new InventoryNotFoundException("Supplier was not found");
        }
        return billRepository.findBySupplierIdOrderByInvoiceDateDescCreatedAtDesc(
                supplierId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"))
        ).map(this::toBillResponse);
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse> getPayments(Long billId, String username) {
        requireActor(username);
        if (billId == null || billId <= 0 || !billRepository.existsById(billId)) {
            throw new InventoryNotFoundException("Supplier bill was not found");
        }
        return paymentRepository.findByBillIdOrderByPaidAtDescIdDesc(billId)
                .stream().map(this::toPaymentResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierBillLandedCostAllocationResponse> getLandedCostAllocations(Long billId, String username) {
        requireActor(username);
        SupplierBill bill = billRepository.findById(billId)
                .orElseThrow(() -> new InventoryNotFoundException("Supplier bill was not found"));
        if (!"LANDED_COST".equals(bill.getBillType())) {
            throw new IllegalArgumentException("This supplier bill is not a landed-cost bill");
        }
        return allocationRepository.findByBillIdOrderByIdAsc(billId)
                .stream().map(this::toAllocationResponse).toList();
    }

    private void allocateLandedCost(SupplierBill bill, List<GoodsReceiptItem> receiptItems) {
        BigDecimal totalBaseValue = receiptItems.stream()
                .map(item -> item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity())).setScale(2, RoundingMode.HALF_UP))
                .reduce(ZERO, BigDecimal::add);
        if (totalBaseValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Landed cost cannot be allocated because received goods have no positive base value");
        }

        BigDecimal remaining = bill.getAmount();
        for (int i = 0; i < receiptItems.size(); i++) {
            GoodsReceiptItem item = receiptItems.get(i);
            BigDecimal base = item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity())).setScale(2, RoundingMode.HALF_UP);
            BigDecimal allocation = i == receiptItems.size() - 1
                    ? remaining
                    : bill.getAmount().multiply(base).divide(totalBaseValue, 2, RoundingMode.DOWN);
            remaining = remaining.subtract(allocation).setScale(2);

            SupplierBillLandedCostAllocation row = new SupplierBillLandedCostAllocation();
            row.setBill(bill);
            row.setGoodsReceiptItem(item);
            row.setBaseGoodsValue(base);
            row.setAllocatedAmount(allocation);
            allocationRepository.save(row);
        }
        allocationRepository.flush();
    }

    private SupplierBillResponse toBillResponse(SupplierBill bill) {
        BigDecimal paid = paymentRepository.sumConfirmedAmountByBillId(bill.getId());
        if (paid == null) paid = ZERO;
        BigDecimal credited = bill.getCreditedAmount() == null ? ZERO : bill.getCreditedAmount();
        BigDecimal outstanding = bill.getAmount().subtract(paid).subtract(credited).setScale(2);
        BigDecimal landedAmount = allocationRepository.sumAllocatedAmountByBillId(bill.getId());
        if (landedAmount == null) landedAmount = ZERO;
        int allocationCount = allocationRepository.findByBillIdOrderByIdAsc(bill.getId()).size();
        PurchaseOrder order = bill.getPurchaseOrder();
        return new SupplierBillResponse(
                bill.getId(), bill.getSupplier().getId(), bill.getSupplier().getName(),
                order == null ? null : order.getId(), order == null ? null : order.getOrderNumber(),
                bill.getInvoiceNumber(), bill.getInvoiceDate(), bill.getDueDate(),
                bill.getAmount(), paid.setScale(2), credited.setScale(2), outstanding, bill.getBillType(), bill.getStatus(),
                allocationCount, landedAmount.setScale(2), bill.getCreatedAt()
        );
    }

    private SupplierCreditNoteResponse toCreditNoteResponse(SupplierBillCreditNote creditNote) {
        SupplierReturn supplierReturn = creditNote.getSupplierReturn();
        return new SupplierCreditNoteResponse(
                creditNote.getId(), creditNote.getBill().getId(), creditNote.getSupplier().getId(),
                supplierReturn == null ? null : supplierReturn.getId(),
                supplierReturn == null ? null : supplierReturn.getReturnNumber(),
                creditNote.getCreditNoteNumber(), creditNote.getIssueDate(), creditNote.getAmount(),
                creditNote.getReason(), creditNote.getStatus(), creditNote.getCreatedByUsername(),
                creditNote.getCreatedAt()
        );
    }

    private SupplierCreditNoteResponse replayCreditNote(SupplierBillCreditNote creditNote, String fingerprint) {
        if (!fingerprint.equals(creditNote.getRequestFingerprint())) {
            throw new IllegalArgumentException("Supplier credit note idempotency key was reused with different details");
        }
        return toCreditNoteResponse(creditNote);
    }

    private SupplierPaymentResponse toPaymentResponse(SupplierPayment payment) {
        return new SupplierPaymentResponse(
                payment.getId(), payment.getBill().getId(), payment.getSupplier().getId(),
                payment.getPaymentMethod(), payment.getAmount(), payment.getReference(),
                payment.getShiftId(), payment.getStatus(), payment.getCreatedByUsername(), payment.getPaidAt()
        );
    }

    private SupplierBillLandedCostAllocationResponse toAllocationResponse(SupplierBillLandedCostAllocation row) {
        GoodsReceiptItem item = row.getGoodsReceiptItem();
        PurchaseOrderItem orderItem = item.getPurchaseOrderItem();
        BigDecimal cumulativeLandedCost = allocationRepository.sumAllocatedAmountByGoodsReceiptItemId(item.getId());
        if (cumulativeLandedCost == null) cumulativeLandedCost = ZERO;
        BigDecimal landedPerUnit = item.getUnitCost().add(
                cumulativeLandedCost.divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP)
        ).setScale(2, RoundingMode.HALF_UP);
        return new SupplierBillLandedCostAllocationResponse(
                row.getId(), row.getBill().getId(), item.getId(), orderItem.getId(),
                orderItem.getVariant().getId(), orderItem.getVariant().getSku(), item.getQuantity(),
                row.getBaseGoodsValue(), row.getAllocatedAmount(), landedPerUnit
        );
    }

    private SupplierBillResponse replayBill(SupplierBill bill, String fingerprint) {
        if (!fingerprint.equals(bill.getRequestFingerprint())) {
            throw new IllegalArgumentException("Supplier bill idempotency key was reused with different details");
        }
        return toBillResponse(bill);
    }

    private SupplierPaymentResponse replayPayment(SupplierPayment payment, String fingerprint) {
        if (!fingerprint.equals(payment.getRequestFingerprint())) {
            throw new IllegalArgumentException("Supplier payment idempotency key was reused with different details");
        }
        return toPaymentResponse(payment);
    }

    private void validateBillRequest(SupplierBillCreateRequest request) {
        if (request == null || request.supplierId() == null || request.supplierId() <= 0
                || request.invoiceNumber() == null || request.invoiceNumber().isBlank()
                || request.invoiceNumber().trim().length() > 100
                || request.invoiceDate() == null || request.amount() == null
                || request.billType() == null || request.billType().isBlank()) {
            throw new IllegalArgumentException("Supplier, invoice number/date, amount and bill type are required");
        }
        if (request.dueDate() != null && request.dueDate().isBefore(request.invoiceDate())) {
            throw new IllegalArgumentException("Supplier bill due date cannot be earlier than its invoice date");
        }
        money(request.amount(), "Supplier bill amount");
        normalizeKey(request.idempotencyKey());
        normalizeBillType(request.billType());
    }

    private void validateCreditNoteRequest(SupplierCreditNoteRequest request) {
        if (request == null || request.creditNoteNumber() == null || request.creditNoteNumber().isBlank()
                || request.creditNoteNumber().trim().length() > 100 || request.issueDate() == null
                || request.amount() == null || request.reason() == null || request.reason().isBlank()
                || request.reason().trim().length() > 500) {
            throw new IllegalArgumentException("Credit note number, issue date, amount and reason are required");
        }
        money(request.amount(), "Supplier credit note amount");
        normalizeKey(request.idempotencyKey());
    }

    private void validatePaymentRequest(SupplierPaymentRequest request) {
        if (request == null || request.amount() == null || request.paymentMethod() == null
                || request.paymentMethod().isBlank()) {
            throw new IllegalArgumentException("Payment amount and method are required");
        }
        money(request.amount(), "Supplier payment amount");
        normalizePaymentMethod(request.paymentMethod());
        normalizeKey(request.idempotencyKey());
    }

    private Admin requireActor(String username) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Authenticated admin account is required");
        return adminRepository.findByUsernameIgnoreCase(username)
                .filter(Admin::isEnabled)
                .filter(admin -> "ADMIN".equalsIgnoreCase(admin.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("Only an enabled administrator can post supplier bills and payments"));
    }

    private BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.scale() > 2 || value.precision() > 14 || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(label + " must be positive and have up to two decimal places");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeBillType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("GOODS", "LANDED_COST", "OTHER").contains(type)) {
            throw new IllegalArgumentException("Bill type must be GOODS, LANDED_COST or OTHER");
        }
        return type;
    }

    private String normalizePaymentMethod(String value) {
        String method = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("CASH", "BANK", "UPI", "OTHER").contains(method)) {
            throw new IllegalArgumentException("Payment method must be CASH, BANK, UPI or OTHER");
        }
        return method;
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100) {
            throw new IllegalArgumentException("A valid idempotency key of 1 to 100 characters is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String fingerprint(String kind, Long id, Object request, String username) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(Map.of(
                            "kind", kind, "id", id == null ? 0L : id,
                            "request", request, "actor", username.trim().toLowerCase(Locale.ROOT)
                    ))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint supplier accounting request", exception);
        }
    }
}

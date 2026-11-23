package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementEntryResponse;
import nmk_backend.dto.RazorpaySettlementExceptionResponse;
import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;
import nmk_backend.dto.RazorpaySettlementSummaryResponse;
import nmk_backend.dto.RazorpaySettlementSyncResponse;
import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.entity.RazorpaySettlement;
import nmk_backend.entity.RazorpaySettlementEntry;
import nmk_backend.entity.RazorpayDispute;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpaySettlementEntryRepository;
import nmk_backend.repository.RazorpaySettlementRepository;
import nmk_backend.repository.RazorpayDisputeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RazorpaySettlementPersistenceService {
    private final RazorpaySettlementRepository settlementRepository;
    private final RazorpaySettlementEntryRepository entryRepository;
    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;
    private final RazorpayDisputeRepository disputeRepository;

    public RazorpaySettlementPersistenceService(
            RazorpaySettlementRepository settlementRepository,
            RazorpaySettlementEntryRepository entryRepository,
            OrderRepository orderRepository,
            OrderRefundRepository refundRepository,
            RazorpayDisputeRepository disputeRepository
    ) {
        this.settlementRepository = settlementRepository;
        this.entryRepository = entryRepository;
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.disputeRepository = disputeRepository;
    }

    @Transactional
    public RazorpaySettlementSyncResponse persist(
            List<RazorpaySettlementProviderSummary> summaries,
            List<RazorpaySettlementReconEntry> rows,
            int pagesFetched,
            int unassignedRows
    ) {
        Map<String, List<RazorpaySettlementReconEntry>> rowsBySettlement = rows.stream()
                .collect(Collectors.groupingBy(
                        RazorpaySettlementReconEntry::settlementId,
                        HashMap::new,
                        Collectors.toList()
                ));

        int persistedEntries = 0;
        for (RazorpaySettlementProviderSummary summary : summaries) {
            if (summary.settlementId() == null || summary.settlementId().isBlank()) {
                throw new IllegalArgumentException("Razorpay returned a settlement without an ID");
            }

            RazorpaySettlement settlement = settlementRepository
                    .findByProviderSettlementIdForUpdate(summary.settlementId())
                    .orElseGet(RazorpaySettlement::new);
            settlement.setProviderSettlementId(summary.settlementId());
            settlement.setStatus(normalizeStatus(summary.status()));
            settlement.setAmountPaise(summary.amountPaise());
            settlement.setFeesPaise(summary.feesPaise());
            settlement.setTaxPaise(summary.taxPaise());
            settlement.setCurrency(normalizeCurrency(summary.currency()));
            settlement.setUtr(blankToNull(summary.utr()));
            settlement.setProviderCreatedAt(summary.createdAt());
            settlement.setRawPayload(summary.rawPayload());
            // Obtain the database ID before upserting child entries.
            settlementRepository.saveAndFlush(settlement);

            List<RazorpaySettlementReconEntry> settlementRows =
                    rowsBySettlement.getOrDefault(summary.settlementId(), List.of());

            long netRowsPaise = 0L;
            long rowFeesPaise = 0L;
            long rowTaxPaise = 0L;
            LocalDateTime latestSettledAt = null;
            Set<String> rowUtrs = new HashSet<>();
            int matched = 0;
            int unmatched = 0;
            int manualReview = 0;
            int pending = 0;

            for (RazorpaySettlementReconEntry row : settlementRows) {
                if (!summary.settlementId().equals(row.settlementId())) {
                    throw new IllegalArgumentException("Settlement row is grouped under the wrong settlement ID");
                }
                if (row.debitPaise() < 0 || row.creditPaise() < 0 || row.amountPaise() < 0
                        || row.feePaise() < 0 || row.taxPaise() < 0) {
                    throw new IllegalArgumentException("Razorpay settlement contains a negative amount");
                }

                RazorpaySettlementEntry entry = entryRepository
                        .findBySettlementAndProviderEntityForUpdate(
                                settlement.getId(), row.entityId(), row.type())
                        .orElseGet(RazorpaySettlementEntry::new);
                entry.setSettlement(settlement);
                copyProviderRow(entry, row);

                MatchResult match = match(row, summary.status());
                entry.setMatchStatus(match.status());
                entry.setMatchMessage(match.message());
                entry.setLocalOrderId(match.localOrderId());
                entry.setLocalRefundId(match.localRefundId());
                entry.setLocalDisputeId(match.localDisputeId());
                entryRepository.save(entry);
                persistedEntries++;

                switch (match.status()) {
                    case "MATCHED" -> matched++;
                    case "UNMATCHED", "MISMATCH" -> unmatched++;
                    case "MANUAL_REVIEW" -> manualReview++;
                    case "PENDING" -> pending++;
                    default -> manualReview++;
                }

                netRowsPaise = Math.addExact(netRowsPaise,
                        Math.subtractExact(row.creditPaise(), row.debitPaise()));
                rowFeesPaise = Math.addExact(rowFeesPaise, row.feePaise());
                rowTaxPaise = Math.addExact(rowTaxPaise, row.taxPaise());
                if (row.settledAt() != null
                        && (latestSettledAt == null || row.settledAt().isAfter(latestSettledAt))) {
                    latestSettledAt = row.settledAt();
                }
                if (row.settlementUtr() != null && !row.settlementUtr().isBlank()) {
                    rowUtrs.add(row.settlementUtr().trim());
                }
            }

            boolean totalsBalanced = netRowsPaise == summary.amountPaise()
                    && rowFeesPaise == summary.feesPaise()
                    && rowTaxPaise == summary.taxPaise()
                    && "INR".equals(normalizeCurrency(summary.currency()));
            String summaryUtr = blankToNull(summary.utr());
            boolean utrBalanced = rowUtrs.size() <= 1
                    && (summaryUtr == null || rowUtrs.isEmpty() || rowUtrs.contains(summaryUtr));
            totalsBalanced = totalsBalanced && utrBalanced;

            settlement.setTotalEntries(settlementRows.size());
            settlement.setMatchedEntries(matched);
            settlement.setUnmatchedEntries(unmatched);
            settlement.setManualReviewEntries(manualReview);
            settlement.setPendingEntries(pending);
            settlement.setNetRowsPaise(netRowsPaise);
            settlement.setTotalsBalanced(totalsBalanced);
            settlement.setSettledAt(latestSettledAt);
            if (summaryUtr == null && rowUtrs.size() == 1) {
                settlement.setUtr(rowUtrs.iterator().next());
            }

            String status = normalizeStatus(summary.status());
            if ("failed".equals(status)) {
                settlement.setReconciliationStatus("FAILED");
            } else if (settlementRows.isEmpty() || !totalsBalanced || unmatched > 0 || manualReview > 0) {
                settlement.setReconciliationStatus("REVIEW_REQUIRED");
            } else if (!"processed".equals(status) || pending > 0) {
                settlement.setReconciliationStatus("PENDING");
            } else {
                settlement.setReconciliationStatus("PROVIDER_RECONCILED");
            }
            settlementRepository.save(settlement);
        }

        String message = "Fetched " + summaries.size() + " settlement batches and persisted "
                + persistedEntries + " provider entries. "
                + unassignedRows + " rows without a settlement ID were excluded.";
        return new RazorpaySettlementSyncResponse(
                summaries.size(), persistedEntries, pagesFetched, message);
    }

    @Transactional(readOnly = true)
    public Page<RazorpaySettlementSummaryResponse> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        return settlementRepository.findAll(PageRequest.of(
                        page, size,
                        Sort.by(Sort.Order.desc("providerCreatedAt"), Sort.Order.desc("id"))))
                .map(this::toSummaryResponse);
    }

    @Transactional(readOnly = true)
    public Page<RazorpaySettlementEntryResponse> entries(Long settlementId, int page, int size) {
        if (settlementId == null || settlementId <= 0 || page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Invalid settlement or page details");
        }
        if (!settlementRepository.existsById(settlementId)) {
            throw new IllegalArgumentException("Settlement not found");
        }
        return entryRepository.findBySettlementId(
                        settlementId,
                        PageRequest.of(page, size, Sort.by(
                                Sort.Order.desc("providerCreatedAt"), Sort.Order.desc("id"))))
                .map(this::toEntryResponse);
    }

    @Transactional(readOnly = true)
    public Page<RazorpaySettlementExceptionResponse> exceptions(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 200");
        }
        List<String> statuses;
        if (status == null || status.isBlank()) {
            statuses = List.of("UNMATCHED", "MISMATCH", "MANUAL_REVIEW", "PENDING");
        } else {
            String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("UNMATCHED", "MISMATCH", "MANUAL_REVIEW", "PENDING").contains(normalized)) {
                throw new IllegalArgumentException("Status must be UNMATCHED, MISMATCH, MANUAL_REVIEW or PENDING");
            }
            statuses = List.of(normalized);
        }
        return entryRepository.findByMatchStatusIn(
                        statuses,
                        PageRequest.of(page, size, Sort.by(
                                Sort.Order.desc("providerCreatedAt"), Sort.Order.desc("id"))))
                .map(this::toExceptionResponse);
    }

    private RazorpaySettlementExceptionResponse toExceptionResponse(RazorpaySettlementEntry entry) {
        RazorpaySettlement settlement = entry.getSettlement();
        return new RazorpaySettlementExceptionResponse(
                settlement.getId(), settlement.getProviderSettlementId(), settlement.getStatus(),
                settlement.getReconciliationStatus(), settlement.getUtr(), settlement.getAmountPaise(),
                entry.getId(), entry.getProviderEntityId(), entry.getEntryType(),
                entry.getDebitPaise(), entry.getCreditPaise(), entry.getAmountPaise(),
                entry.getFeePaise(), entry.getTaxPaise(), entry.getCurrency(),
                entry.isOnHold(), entry.isSettled(), entry.getProviderCreatedAt(), entry.getProviderSettledAt(),
                entry.getPaymentId(), entry.getOrderId(), entry.getMatchStatus(), entry.getMatchMessage(),
                entry.getLocalOrderId(), entry.getLocalRefundId(), entry.getLocalDisputeId()
        );
    }

    private void copyProviderRow(RazorpaySettlementEntry entry, RazorpaySettlementReconEntry row) {
        entry.setProviderEntityId(row.entityId());
        entry.setEntryType(row.type());
        entry.setDebitPaise(row.debitPaise());
        entry.setCreditPaise(row.creditPaise());
        entry.setAmountPaise(row.amountPaise());
        entry.setFeePaise(row.feePaise());
        entry.setTaxPaise(row.taxPaise());
        entry.setCurrency(normalizeCurrency(row.currency()));
        entry.setOnHold(row.onHold());
        entry.setSettled(row.settled());
        entry.setProviderCreatedAt(row.createdAt());
        entry.setProviderSettledAt(row.settledAt());
        entry.setPaymentId(blankToNull(row.paymentId()));
        entry.setOrderId(blankToNull(row.orderId()));
        entry.setSettlementUtr(blankToNull(row.settlementUtr()));
        entry.setMethod(blankToNull(row.method()));
        entry.setDisputeId(blankToNull(row.disputeId()));
        entry.setRawPayload(row.rawPayload());
    }

    private MatchResult match(RazorpaySettlementReconEntry row, String settlementStatus) {
        if (!"processed".equals(normalizeStatus(settlementStatus))
                || !row.settled() || row.onHold()) {
            return new MatchResult("PENDING", null, null, null,
                    row.onHold() ? "Provider has placed this transaction on hold."
                            : "Provider has not marked this transaction as settled.");
        }
        if (!"INR".equals(normalizeCurrency(row.currency()))) {
            return new MatchResult("MISMATCH", null, null, null,
                    "Settlement currency is not INR.");
        }

        if ("payment".equals(row.type())) {
            Order order = null;
            if (row.orderId() != null && !row.orderId().isBlank()) {
                order = orderRepository.findByRazorpayOrderId(row.orderId()).orElse(null);
            }
            if (order == null && row.entityId() != null && !row.entityId().isBlank()) {
                order = orderRepository.findByRazorpayPaymentId(row.entityId()).orElse(null);
            }
            if (order == null) {
                return new MatchResult("UNMATCHED", null, null, null,
                        "No local order matches this Razorpay payment/order ID.");
            }
            if (!row.entityId().equals(order.getRazorpayPaymentId())) {
                return new MatchResult("MISMATCH", order.getId(), null, null,
                        "Provider payment ID does not match the local order.");
            }
            if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())
                    && !"REFUNDED".equalsIgnoreCase(order.getPaymentStatus())) {
                return new MatchResult("MISMATCH", order.getId(), null, null,
                        "Local order is not recorded as paid.");
            }
            if (toPaise(order.getTotalAmount()) != row.amountPaise()) {
                return new MatchResult("MISMATCH", order.getId(), null, null,
                        "Provider gross payment amount differs from the local order total.");
            }
            return new MatchResult("MATCHED", order.getId(), null, null,
                    "Payment ID and gross order amount match.");
        }

        if ("refund".equals(row.type())) {
            Order order = row.paymentId() == null || row.paymentId().isBlank()
                    ? null : orderRepository.findByRazorpayPaymentId(row.paymentId()).orElse(null);
            OrderRefund refund = row.entityId() == null || row.entityId().isBlank()
                    ? null : refundRepository.findByRazorpayRefundId(row.entityId()).orElse(null);
            if (order == null || refund == null) {
                return new MatchResult("UNMATCHED",
                        order == null ? null : order.getId(), null, null,
                        "No local paid order and processed refund ledger row match this provider refund.");
            }
            if (!refund.getOrder().getId().equals(order.getId())
                    || !row.paymentId().equals(refund.getRazorpayPaymentId())
                    || refund.getAmountPaise() != row.amountPaise()
                    || !"INR".equalsIgnoreCase(refund.getCurrency())
                    || !"PROCESSED".equalsIgnoreCase(refund.getStatus())) {
                return new MatchResult("MISMATCH", order.getId(), refund.getId(), null,
                        "Provider refund does not match the local refund amount, order or processed status.");
            }
            return new MatchResult("MATCHED", order.getId(), refund.getId(), null,
                    "Provider refund ID and amount match the local refund ledger.");
        }

        if ("dispute".equals(row.type())) {
            RazorpayDispute dispute = row.entityId() == null || row.entityId().isBlank()
                    ? null : disputeRepository.findByProviderDisputeId(row.entityId()).orElse(null);
            if (dispute == null) {
                return new MatchResult("UNMATCHED", null, null, null,
                        "No local dispute ledger row matches this provider dispute.");
            }
            if (!dispute.getProviderPaymentId().equals(row.paymentId())
                    || !"INR".equalsIgnoreCase(dispute.getCurrency())
                    || !"LOST".equalsIgnoreCase(dispute.getStatus())
                    || dispute.getAmountDeductedPaise() != row.amountPaise()) {
                return new MatchResult("MISMATCH", dispute.getLocalOrderId(), null, dispute.getId(),
                        "Provider dispute debit does not match the local lost-dispute amount and payment.");
            }
            return new MatchResult("MATCHED", dispute.getLocalOrderId(), null, dispute.getId(),
                    "Provider dispute debit matches the local lost-dispute ledger.");
        }

        return new MatchResult("MANUAL_REVIEW", null, null, null,
                "This provider entry type is not mapped to an NMK local ledger.");
    }

    private long toPaise(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (Exception exception) {
            return -1L;
        }
    }

    private String normalizeStatus(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeCurrency(String value) {
        return value == null || value.isBlank()
                ? "INR" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private RazorpaySettlementSummaryResponse toSummaryResponse(RazorpaySettlement settlement) {
        return new RazorpaySettlementSummaryResponse(
                settlement.getId(),
                settlement.getProviderSettlementId(),
                settlement.getStatus(),
                settlement.getAmountPaise(),
                settlement.getFeesPaise(),
                settlement.getTaxPaise(),
                settlement.getCurrency(),
                settlement.getUtr(),
                settlement.getProviderCreatedAt(),
                settlement.getSettledAt(),
                settlement.getLastSyncedAt(),
                settlement.getReconciliationStatus(),
                settlement.getTotalEntries(),
                settlement.getMatchedEntries(),
                settlement.getUnmatchedEntries(),
                settlement.getManualReviewEntries(),
                settlement.getPendingEntries(),
                settlement.getNetRowsPaise(),
                settlement.isTotalsBalanced()
        );
    }

    private RazorpaySettlementEntryResponse toEntryResponse(RazorpaySettlementEntry entry) {
        return new RazorpaySettlementEntryResponse(
                entry.getId(),
                entry.getProviderEntityId(),
                entry.getEntryType(),
                entry.getDebitPaise(),
                entry.getCreditPaise(),
                entry.getAmountPaise(),
                entry.getFeePaise(),
                entry.getTaxPaise(),
                entry.getCurrency(),
                entry.isOnHold(),
                entry.isSettled(),
                entry.getProviderCreatedAt(),
                entry.getProviderSettledAt(),
                entry.getPaymentId(),
                entry.getOrderId(),
                entry.getSettlementUtr(),
                entry.getMethod(),
                entry.getDisputeId(),
                entry.getMatchStatus(),
                entry.getMatchMessage(),
                entry.getLocalOrderId(),
                entry.getLocalRefundId(),
                entry.getLocalDisputeId()
        );
    }

    private record MatchResult(String status, Long localOrderId, Long localRefundId, Long localDisputeId, String message) {}
}

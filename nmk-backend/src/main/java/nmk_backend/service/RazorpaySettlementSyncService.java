package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;
import nmk_backend.dto.RazorpaySettlementSyncResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RazorpaySettlementSyncService {
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 50;

    private final RazorpayService razorpayService;
    private final RazorpaySettlementGateway gateway;
    private final RazorpaySettlementPersistenceService persistenceService;

    public RazorpaySettlementSyncService(
            RazorpayService razorpayService,
            RazorpaySettlementGateway gateway,
            RazorpaySettlementPersistenceService persistenceService
    ) {
        this.razorpayService = razorpayService;
        this.gateway = gateway;
        this.persistenceService = persistenceService;
    }

    public RazorpaySettlementSyncResponse sync(int year, int month, Integer day) {
        validateDate(year, month, day);
        razorpayService.requirePaymentCredentials();

        Map<String, RazorpaySettlementReconEntry> uniqueRows = new LinkedHashMap<>();
        int pagesFetched = 0;
        int unassignedRows = 0;
        boolean complete = false;

        for (int page = 0; page < MAX_PAGES; page++) {
            int skip = page * PAGE_SIZE;
            List<RazorpaySettlementReconEntry> rows =
                    gateway.fetchReconPage(year, month, day, PAGE_SIZE, skip);
            pagesFetched++;

            for (RazorpaySettlementReconEntry row : rows) {
                if (row.settlementId() == null || row.settlementId().isBlank()) {
                    unassignedRows++;
                    continue;
                }
                if (row.entityId() == null || row.entityId().isBlank()
                        || row.type() == null || row.type().isBlank()) {
                    throw new IllegalStateException(
                            "Razorpay settlement report contains a row without an entity ID or type");
                }

                String key = row.settlementId() + "|" + row.entityId() + "|" + row.type();
                RazorpaySettlementReconEntry previous = uniqueRows.putIfAbsent(key, row);
                if (previous != null && !Objects.equals(previous.rawPayload(), row.rawPayload())) {
                    throw new IllegalStateException(
                            "Razorpay returned conflicting duplicate settlement rows; no data was imported");
                }
            }

            if (rows.size() < PAGE_SIZE) {
                complete = true;
                break;
            }
        }

        if (!complete) {
            throw new IllegalStateException(
                    "Settlement report exceeded the safe 50,000-row sync limit. Sync a single day instead.");
        }

        List<RazorpaySettlementReconEntry> allRows = new ArrayList<>(uniqueRows.values());
        List<String> settlementIds = allRows.stream()
                .map(RazorpaySettlementReconEntry::settlementId)
                .distinct()
                .sorted()
                .toList();

        List<RazorpaySettlementProviderSummary> summaries = new ArrayList<>();
        for (String settlementId : settlementIds) {
            RazorpaySettlementProviderSummary summary = gateway.fetchSettlement(settlementId);
            if (summary == null || !settlementId.equals(summary.settlementId())) {
                throw new IllegalStateException(
                        "Razorpay settlement details did not match the report settlement ID");
            }
            summaries.add(summary);
        }

        return persistenceService.persist(summaries, allRows, pagesFetched, unassignedRows);
    }

    private void validateDate(int year, int month, Integer day) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (year < 2020 || year > today.getYear() || month < 1 || month > 12) {
            throw new IllegalArgumentException("Choose a valid past year and month.");
        }
        LocalDate requested;
        try {
            requested = day == null
                    ? LocalDate.of(year, month, 1)
                    : LocalDate.of(year, month, day);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Choose a valid calendar date.");
        }
        if (requested.isAfter(today)) {
            throw new IllegalArgumentException("Future settlement dates cannot be reconciled.");
        }
    }
}

package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;
import nmk_backend.dto.RazorpaySettlementSyncResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpaySettlementSyncServiceTest {
    @Mock private RazorpayService razorpayService;
    @Mock private RazorpaySettlementGateway gateway;
    @Mock private RazorpaySettlementPersistenceService persistenceService;

    private RazorpaySettlementSyncService service() {
        return new RazorpaySettlementSyncService(razorpayService, gateway, persistenceService);
    }

    @Test
    void fetchesReportDeduplicatesRowsAndPersistsSettlementDetails() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime now = LocalDateTime.now();
        RazorpaySettlementReconEntry row = new RazorpaySettlementReconEntry(
                "pay_123", "payment", 0L, 97100L, 100000L, "INR",
                2900L, 0L, false, true, now, now, "setl_123",
                "utr_123", "", "order_123", "card", "", "{}");
        RazorpaySettlementReconEntry unassigned = new RazorpaySettlementReconEntry(
                "pay_unsettled", "payment", 0L, 10000L, 10000L, "INR",
                0L, 0L, false, false, now, null, "",
                "", "", "order_unsettled", "upi", "", "{}");
        RazorpaySettlementProviderSummary summary = new RazorpaySettlementProviderSummary(
                "setl_123", 97100L, "processed", 2900L, 0L,
                "INR", "utr_123", now, "{}");
        RazorpaySettlementSyncResponse expected =
                new RazorpaySettlementSyncResponse(1, 1, 1, "synced");

        when(gateway.fetchReconPage(today.getYear(), today.getMonthValue(), null, 1000, 0))
                .thenReturn(List.of(row, row, unassigned));
        when(gateway.fetchSettlement("setl_123")).thenReturn(summary);
        when(persistenceService.persist(List.of(summary), List.of(row), 1, 1))
                .thenReturn(expected);

        var result = service().sync(today.getYear(), today.getMonthValue(), null);

        assertEquals(expected, result);
        verify(gateway).fetchSettlement("setl_123");
        verify(persistenceService).persist(List.of(summary), List.of(row), 1, 1);
    }

    @Test
    void rejectsFutureSettlementDatesBeforeCallingProvider() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);

        assertThrows(IllegalArgumentException.class, () ->
                service().sync(tomorrow.getYear(), tomorrow.getMonthValue(), tomorrow.getDayOfMonth()));

        verifyNoInteractions(gateway, persistenceService);
    }
}

package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;
import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpaySettlementEntryRepository;
import nmk_backend.repository.RazorpaySettlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class RazorpaySettlementPersistenceMySqlTest {
    @Autowired private RazorpaySettlementPersistenceService persistenceService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RazorpaySettlementRepository settlementRepository;
    @Autowired private RazorpaySettlementEntryRepository entryRepository;

    @Test
    void unmatchedProviderRowsAreVisibleInGlobalExceptionQueue() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String settlementId = "setl_unmatched_" + suffix;
        String paymentId = "pay_unmatched_" + suffix;
        String orderId = "order_missing_" + suffix;
        LocalDateTime now = LocalDateTime.now();

        RazorpaySettlementProviderSummary summary = new RazorpaySettlementProviderSummary(
                settlementId, 97000L, "processed", 3000L, 0L,
                "INR", "utr_" + suffix, now, "{}");
        RazorpaySettlementReconEntry unmatchedPayment = new RazorpaySettlementReconEntry(
                paymentId, "payment", 0L, 97000L, 100000L, "INR",
                3000L, 0L, false, true, now, now, settlementId,
                "utr_" + suffix, "", orderId, "card", "", "{}");

        persistenceService.persist(List.of(summary), List.of(unmatchedPayment), 1, 0);

        var exceptions = persistenceService.exceptions("UNMATCHED", 0, 25);

        assertTrue(
                exceptions.getContent().stream().anyMatch(exception ->
                        settlementId.equals(exception.providerSettlementId())
                                && paymentId.equals(exception.providerEntityId())
                                && "UNMATCHED".equals(exception.matchStatus())),
                "The newly persisted unmatched provider row must appear in the global exception queue"
        );
    }

    @Test
    void settlementSyncIsIdempotentAndPersistsProviderMatchedPaymentRows() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String paymentId = "pay_" + suffix;
        String orderId = "order_" + suffix;
        String settlementId = "setl_" + suffix;
        LocalDateTime now = LocalDateTime.now();

        Order order = new Order();
        order.setCustomerName("Settlement persistence test");
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayOrderId(orderId);
        order.setRazorpayPaymentId(paymentId);
        order = orderRepository.saveAndFlush(order);

        RazorpaySettlementProviderSummary summary = new RazorpaySettlementProviderSummary(
                settlementId, 97100L, "processed", 2900L, 0L,
                "INR", "utr_" + suffix, now, "{}");
        RazorpaySettlementReconEntry payment = new RazorpaySettlementReconEntry(
                paymentId, "payment", 0L, 97100L, 100000L, "INR",
                2900L, 0L, false, true, now, now, settlementId,
                "utr_" + suffix, "", orderId, "card", "", "{}");

        persistenceService.persist(List.of(summary), List.of(payment), 1, 0);
        persistenceService.persist(List.of(summary), List.of(payment), 1, 0);

        var settlement = settlementRepository.findByProviderSettlementId(settlementId).orElseThrow();
        var entries = entryRepository.findBySettlementId(settlement.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertEquals("PROVIDER_RECONCILED", settlement.getReconciliationStatus());
        assertTrue(settlement.isTotalsBalanced());
        assertEquals(1, settlement.getMatchedEntries());
        assertEquals(1, entries.getTotalElements());
        assertEquals(order.getId(), entries.getContent().getFirst().getLocalOrderId());
    }
}

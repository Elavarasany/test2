package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.dto.RazorpayOrderSnapshot;
import nmk_backend.dto.RazorpayPaymentAttemptSnapshot;
import java.util.Optional;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StalePaymentCreationReviewServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RazorpayOrderStatusGateway providerGateway;

    @Test
    void returnsOnlyPendingOrdersWithAnOutstandingStockReservation() {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PENDING");
        order.setPaymentCreationStatus("IN_PROGRESS");
        ReflectionTestUtils.setField(order, "orderDate", now.minusMinutes(30));
        order.setStockReserved(true);
        order.setInventoryLocationId(7L);
        order.setReservationExpiresAt(now.minusMinutes(5));

        when(orderRepository.findExpiredStalePaymentCreations(
                eq(List.of("IN_PROGRESS", "CREATED")),
                eq("PENDING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PageRequest.class)
        )).thenReturn(new PageImpl<>(List.of(order)));

        var page = new StalePaymentCreationReviewService(orderRepository, providerGateway)
                .getStalePaymentCreations(0, 25, 10);

        assertEquals(1, page.getTotalElements());
        var item = page.getContent().get(0);
        assertEquals(new BigDecimal("125.00"), item.totalAmount());
        assertEquals("IN_PROGRESS", item.paymentCreationStatus());
        assertTrue(item.stockReserved());
        assertEquals(7L, item.inventoryLocationId());
        assertTrue(item.ageMinutes() >= 30);
    }

    @Test
    void validatesPaginationAndReviewAgeWithoutQueryingDatabase() {
        var service = new StalePaymentCreationReviewService(orderRepository, providerGateway);
        assertThrows(IllegalArgumentException.class, () -> service.getStalePaymentCreations(-1, 25, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getStalePaymentCreations(0, 101, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getStalePaymentCreations(0, 25, 0));
        verifyNoInteractions(orderRepository, providerGateway);
    }

    @Test
    void fetchesProviderOrderAndPaymentsWithoutChangingLocalOrder() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 42L);
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setRazorpayOrderId("order_Abc123");
        order.setPaymentStatus("PENDING");
        order.setPaymentCreationStatus("IN_PROGRESS");
        order.setStockReserved(true);
        order.setReservationExpiresAt(now.minusMinutes(5));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(providerGateway.fetchOrderAndPayments("order_Abc123")).thenReturn(
                new RazorpayOrderSnapshot(
                        "order_Abc123", "attempted", 12500, 0, 12500, "INR", 2,
                        List.of(new RazorpayPaymentAttemptSnapshot("pay_Abc123", "failed", 12500, "INR", false))
                )
        );

        var result = new StalePaymentCreationReviewService(orderRepository, providerGateway)
                .getProviderStatus(42L);

        assertEquals("attempted", result.providerOrderStatus());
        assertEquals(2, result.providerAttempts());
        assertTrue(result.providerAmountMatchesLocal());
        assertEquals("failed", result.paymentAttempts().get(0).status());
        assertTrue(result.stockReserved());
        assertEquals("PENDING", order.getPaymentStatus());
        assertTrue(order.isStockReserved());
        verify(providerGateway).fetchOrderAndPayments("order_Abc123");
    }

    @Test
    void refusesProviderLookupBeforeReservationExpires() {
        Order order = new Order();
        order.setRazorpayOrderId("order_Abc123");
        order.setPaymentStatus("PENDING");
        order.setStockReserved(true);
        order.setReservationExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        var service = new StalePaymentCreationReviewService(orderRepository, providerGateway);
        assertThrows(IllegalStateException.class, () -> service.getProviderStatus(42L));
        verifyNoInteractions(providerGateway);
    }

    @Test
    void failsClosedWhenProviderLookupFails() throws Exception {
        Order order = new Order();
        order.setRazorpayOrderId("order_Abc123");
        order.setPaymentStatus("PENDING");
        order.setStockReserved(true);
        order.setReservationExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(providerGateway.fetchOrderAndPayments("order_Abc123")).thenThrow(new RuntimeException("provider unavailable"));

        var service = new StalePaymentCreationReviewService(orderRepository, providerGateway);
        assertThrows(StalePaymentCreationReviewService.ProviderStatusUnavailableException.class,
                () -> service.getProviderStatus(42L));
        assertTrue(order.isStockReserved());
        assertEquals("PENDING", order.getPaymentStatus());
    }
}

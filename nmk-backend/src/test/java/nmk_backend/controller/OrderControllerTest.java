
package nmk_backend.controller;

import nmk_backend.dto.CreateCheckoutResponse;
import nmk_backend.dto.CreateOrderRequest;
import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;
import nmk_backend.service.OrderService;
import nmk_backend.service.RazorpayService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private Order pendingOrder;

    @InjectMocks
    private OrderController orderController;

    private CreateOrderRequest request;

    @BeforeEach
    void setUp() {
        request = new CreateOrderRequest();
    }

    @Test
    void checkoutReturns503WhenCredentialsAreMissing() throws Exception {

        doThrow(new IllegalStateException("Credentials missing"))
                .when(razorpayService)
                .requirePaymentCredentials();

        ResponseEntity<?> response =
                orderController.createCheckout(request);

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode()
        );

        verifyNoInteractions(orderService);
        verifyNoInteractions(orderRepository);

        verify(razorpayService, never()).createOrder(anyInt());
    }

    @Test
    void checkoutReleasesStockWhenAmountIsInvalid() throws Exception {

        when(orderService.createPendingOrder(request))
                .thenReturn(pendingOrder);

        when(pendingOrder.getTotalAmount())
                .thenReturn(BigDecimal.ZERO);

        when(pendingOrder.getId())
                .thenReturn(10L);

        ResponseEntity<?> response =
                orderController.createCheckout(request);

        assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode()
        );

        verify(orderService).releaseReservation(10L);

        verify(orderRepository, never())
                .saveAndFlush(any());

        verify(razorpayService, never())
                .createOrder(anyInt());
    }
    @Test
    void checkoutReleasesStockWhenAmountHasFractionalPaise()
            throws Exception {

        when(orderService.createPendingOrder(request))
                .thenReturn(pendingOrder);

        when(pendingOrder.getTotalAmount())
                .thenReturn(new BigDecimal("100.001"));

        when(pendingOrder.getId())
                .thenReturn(11L);

        ResponseEntity<?> response =
                orderController.createCheckout(request);

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode()
        );

        verify(orderService).releaseReservation(11L);

        verify(orderRepository, never())
                .saveAndFlush(any());

        verify(razorpayService, never())
                .createOrder(anyInt());
    }

    @Test
    void checkoutReleasesStockWhenInProgressStatusCannotBeSaved()
            throws Exception {

        when(orderService.createPendingOrder(request))
                .thenReturn(pendingOrder);

        when(pendingOrder.getTotalAmount())
                .thenReturn(new BigDecimal("100.00"));

        when(pendingOrder.getId())
                .thenReturn(15L);

        when(orderRepository.saveAndFlush(pendingOrder))
                .thenThrow(new RuntimeException("Database unavailable"));

        ResponseEntity<?> response =
                orderController.createCheckout(request);

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode()
        );

        verify(pendingOrder)
                .setPaymentCreationStatus("IN_PROGRESS");

        verify(orderService).releaseReservation(15L);

        verify(razorpayService, never())
                .createOrder(anyInt());
    }

    @Test
    void checkoutRetainsStockWhenRazorpayCallFails() throws Exception {

        when(orderService.createPendingOrder(request))
                .thenReturn(pendingOrder);

        when(pendingOrder.getTotalAmount())
                .thenReturn(new BigDecimal("100.00"));

        when(pendingOrder.getId())
                .thenReturn(20L);

        when(razorpayService.createOrder(10000))
                .thenThrow(new RuntimeException("Provider unavailable"));

        ResponseEntity<?> response =
                orderController.createCheckout(request);

        assertEquals(
                HttpStatus.BAD_GATEWAY,
                response.getStatusCode()
        );

        verify(pendingOrder)
                .setPaymentCreationStatus("IN_PROGRESS");

        verify(orderRepository, times(1))
                .saveAndFlush(pendingOrder);

        verify(orderService, never())
                .releaseReservation(anyLong());

        verify(orderRepository, never())
                .save(any());
    }

    @Test
    void checkoutSavesRazorpayOrderAndMarksCreationAsCompleted()
            throws Exception {

        when(orderService.createPendingOrder(request))
                .thenReturn(pendingOrder);

        when(pendingOrder.getTotalAmount())
                .thenReturn(new BigDecimal("250.00"));

        when(pendingOrder.getId())
                .thenReturn(30L);

        com.razorpay.Order razorpayOrder =
                mock(com.razorpay.Order.class);

        when(razorpayService.createOrder(25000))
                .thenReturn(razorpayOrder);

        when(razorpayOrder.get("id"))
                .thenReturn("order_test_123");

        when(razorpayService.getKeyId())
                .thenReturn("rzp_test_key");

        ResponseEntity<?> response =
                orderController.createCheckout(request);

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode()
        );

        assertInstanceOf(
                CreateCheckoutResponse.class,
                response.getBody()
        );

        CreateCheckoutResponse body =
                (CreateCheckoutResponse) response.getBody();

        assertEquals(30L, body.getDatabaseOrderId());
        assertEquals("order_test_123", body.getRazorpayOrderId());
        assertEquals(25000, body.getAmount());
        assertEquals("INR", body.getCurrency());
        assertEquals("rzp_test_key", body.getKeyId());

        verify(pendingOrder)
                .setPaymentCreationStatus("IN_PROGRESS");

        verify(pendingOrder)
                .setRazorpayOrderId("order_test_123");

        verify(pendingOrder)
                .setPaymentCreationStatus("CREATED");

        verify(orderRepository, times(2))
                .saveAndFlush(pendingOrder);

        verify(orderService, never())
                .releaseReservation(anyLong());
    }
}

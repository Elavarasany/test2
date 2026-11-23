package nmk_backend.service;

import nmk_backend.dto.RazorpayOrderSnapshot;

public interface RazorpayOrderStatusGateway {
    RazorpayOrderSnapshot fetchOrderAndPayments(String razorpayOrderId) throws Exception;
}

package nmk_backend.service;

public class RazorpayRefundGatewayException extends RuntimeException {
    private final int httpStatus;

    public RazorpayRefundGatewayException(int httpStatus) {
        super("Razorpay refund request returned HTTP " + httpStatus);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}

package nmk_backend.service;

public class RazorpaySettlementGatewayException extends RuntimeException {
    private final int httpStatus;

    public RazorpaySettlementGatewayException(int httpStatus) {
        super("Razorpay settlement API returned HTTP " + httpStatus);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() { return httpStatus; }
}

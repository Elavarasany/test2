package nmk_backend.service;

import com.razorpay.RazorpayClient;
import nmk_backend.dto.RazorpayOrderSnapshot;
import nmk_backend.dto.RazorpayPaymentAttemptSnapshot;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class RazorpaySdkOrderStatusGateway implements RazorpayOrderStatusGateway {

    @Value("${razorpay.key.id}")
    private String keyId;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    @Override
    public RazorpayOrderSnapshot fetchOrderAndPayments(String razorpayOrderId) throws Exception {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException("Razorpay API credentials are not configured");
        }
        if (razorpayOrderId == null || !razorpayOrderId.matches("order_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid Razorpay order identifier");
        }

        RazorpayClient client = new RazorpayClient(keyId, keySecret);
        com.razorpay.Order providerOrder = client.orders.fetch(razorpayOrderId);
        List<com.razorpay.Payment> paymentItems = client.orders.fetchPayments(razorpayOrderId);

        JSONObject orderJson = providerOrder.toJson();
        List<RazorpayPaymentAttemptSnapshot> payments = new ArrayList<>();

        if (paymentItems != null) {
            for (com.razorpay.Payment paymentEntity : paymentItems) {
                JSONObject payment = paymentEntity.toJson();
                payments.add(new RazorpayPaymentAttemptSnapshot(
                        safeString(payment, "id"),
                        safeString(payment, "status"),
                        exactInt(payment.opt("amount")),
                        safeString(payment, "currency"),
                        payment.has("captured") && !payment.isNull("captured")
                                ? payment.optBoolean("captured") : null
                ));
            }
        }

        return new RazorpayOrderSnapshot(
                safeString(orderJson, "id"),
                safeString(orderJson, "status"),
                exactInt(orderJson.opt("amount")),
                exactInt(orderJson.opt("amount_paid")),
                exactInt(orderJson.opt("amount_due")),
                safeString(orderJson, "currency"),
                exactInt(orderJson.opt("attempts")),
                List.copyOf(payments)
        );
    }

    private static String safeString(JSONObject json, String key) {
        return json.has(key) && !json.isNull(key) ? json.optString(key, null) : null;
    }

    private static Integer exactInt(Object value) {
        if (!(value instanceof Number number)) return null;
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }
}

package nmk_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;

@Component
public class HttpRazorpayRefundGateway implements RazorpayRefundGateway {
    private final String keyId;
    private final String keySecret;
    private final RestClient client;

    public HttpRazorpayRefundGateway(
            @Value("${razorpay.key.id:}") String keyId,
            @Value("${razorpay.key.secret:}") String keySecret
    ) {
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.client = RestClient.builder()
                .baseUrl("https://api.razorpay.com")
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (!this.keyId.isBlank() && !this.keySecret.isBlank()) {
                        headers.setBasicAuth(this.keyId, this.keySecret);
                    }
                })
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RazorpayRefundProviderResponse createNormalRefund(
            String paymentId,
            long amountPaise,
            String receipt,
            String idempotencyKey,
            String reason,
            Long orderId
    ) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new nmk_backend.exception.PaymentConfigurationException(
                    "Razorpay payment credentials are not configured");
        }

        // Stable field order keeps the serialized payload identical on same-key retries.
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("reason", reason);
        notes.put("local_order_id", String.valueOf(orderId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("speed", "normal");
        body.put("receipt", receipt);
        body.put("notes", notes);

        try {
            Map<String, Object> response = client.post()
                    .uri("/v1/payments/{paymentId}/refund", paymentId)
                    .header("X-Refund-Idempotency", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new IllegalStateException("Razorpay returned an empty refund response");
            }

            Object amount = response.get("amount");
            long responseAmount = amount instanceof Number number
                    ? new java.math.BigDecimal(number.toString()).longValueExact()
                    : Long.parseLong(String.valueOf(amount));

            return new RazorpayRefundProviderResponse(
                    stringValue(response.get("id")),
                    stringValue(response.get("payment_id")),
                    responseAmount,
                    stringValue(response.get("currency")),
                    stringValue(response.get("status")),
                    stringValue(response.get("receipt"))
            );
        } catch (RestClientResponseException exception) {
            throw new RazorpayRefundGatewayException(exception.getStatusCode().value());
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

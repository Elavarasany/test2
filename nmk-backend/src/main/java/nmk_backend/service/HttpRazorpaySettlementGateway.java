package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;
import nmk_backend.exception.PaymentConfigurationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class HttpRazorpaySettlementGateway implements RazorpaySettlementGateway {
    private final String keyId;
    private final String keySecret;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HttpRazorpaySettlementGateway(
            @Value("${razorpay.key.id:}") String keyId,
            @Value("${razorpay.key.secret:}") String keySecret,
            ObjectMapper objectMapper
    ) {
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(25));

        this.client = RestClient.builder()
                .baseUrl("https://api.razorpay.com")
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (!this.keyId.isBlank() && !this.keySecret.isBlank()) {
                        headers.setBasicAuth(this.keyId, this.keySecret);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    }
                })
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RazorpaySettlementReconEntry> fetchReconPage(
            int year, int month, Integer day, int count, int skip
    ) {
        requireCredentials();
        try {
            Map<String, Object> response = client.get()
                    .uri(builder -> {
                        var uri = builder.path("/v1/settlements/recon/combined")
                                .queryParam("year", year)
                                .queryParam("month", month)
                                .queryParam("count", count)
                                .queryParam("skip", skip);
                        if (day != null) uri.queryParam("day", day);
                        return uri.build();
                    })
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new IllegalStateException("Razorpay returned an empty settlement reconciliation response");
            }
            Object itemsValue = response.get("items");
            if (!(itemsValue instanceof List<?> items)) {
                throw new IllegalStateException("Razorpay settlement reconciliation response has no items array");
            }

            List<RazorpaySettlementReconEntry> result = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> row)) {
                    throw new IllegalStateException("Razorpay settlement report contains an invalid row");
                }
                result.add(new RazorpaySettlementReconEntry(
                        stringValue(row.get("entity_id")),
                        stringValue(row.get("type")).toLowerCase(java.util.Locale.ROOT),
                        longValue(row.get("debit")),
                        longValue(row.get("credit")),
                        longValue(row.get("amount")),
                        currencyValue(row.get("currency")),
                        longValue(row.get("fee")),
                        longValue(row.get("tax")),
                        booleanValue(row.get("on_hold")),
                        booleanValue(row.get("settled")),
                        dateTimeValue(row.get("created_at")),
                        dateTimeValue(row.get("settled_at")),
                        stringValue(row.get("settlement_id")),
                        stringValue(row.get("settlement_utr")),
                        stringValue(row.get("payment_id")),
                        stringValue(row.get("order_id")),
                        stringValue(row.get("method")),
                        stringValue(row.get("dispute_id")),
                        jsonValue(row)
                ));
            }
            return result;
        } catch (RestClientResponseException exception) {
            throw new RazorpaySettlementGatewayException(exception.getStatusCode().value());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RazorpaySettlementProviderSummary fetchSettlement(String settlementId) {
        requireCredentials();
        try {
            Map<String, Object> response = client.get()
                    .uri("/v1/settlements/{settlementId}", settlementId)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new IllegalStateException("Razorpay returned an empty settlement detail response");
            }
            return new RazorpaySettlementProviderSummary(
                    stringValue(response.get("id")),
                    longValue(response.get("amount")),
                    stringValue(response.get("status")).toLowerCase(java.util.Locale.ROOT),
                    longValue(response.get("fees")),
                    longValue(response.get("tax")),
                    currencyValue(response.get("currency")),
                    stringValue(response.get("utr")),
                    dateTimeValue(response.get("created_at")),
                    jsonValue(response)
            );
        } catch (RestClientResponseException exception) {
            throw new RazorpaySettlementGatewayException(exception.getStatusCode().value());
        }
    }

    private void requireCredentials() {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new PaymentConfigurationException(
                    "Razorpay settlement API credentials are not configured");
        }
    }

    private long longValue(Object value) {
        if (value == null) return 0L;
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (Exception exception) {
            throw new IllegalStateException("Razorpay settlement response contains an invalid integer amount");
        }
    }

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String currencyValue(Object value) {
        String currency = stringValue(value);
        return currency.isBlank() ? "INR" : currency.toUpperCase(java.util.Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private LocalDateTime dateTimeValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            long seconds = longValue(value);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Razorpay settlement response contains an invalid timestamp");
        }
    }

    private String jsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to retain raw Razorpay settlement data", exception);
        }
    }
}

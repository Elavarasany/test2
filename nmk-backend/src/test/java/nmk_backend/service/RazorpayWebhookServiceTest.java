package nmk_backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookServiceTest {
    @Mock private RazorpayWebhookEventRepository eventRepository;
    @Mock private RazorpayWebhookProcessor processor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void storesMinimalWebhookPayloadWithoutCustomerOrEvidenceFields() throws Exception {
        String payload = """
                {
                  "event":"payment.dispute.created",
                  "created_at":1700000100,
                  "payload":{
                    "payment":{"entity":{
                      "id":"pay_123",
                      "order_id":"order_123",
                      "amount":10000,
                      "email":"customer@example.com",
                      "contact":"+919999999999",
                      "vpa":"customer@upi",
                      "card":{"last4":"1234"},
                      "notes":{"customer":"private note"}
                    }},
                    "dispute":{"entity":{
                      "id":"disp_123",
                      "payment_id":"pay_123",
                      "amount":10000,
                      "reason_code":"goods_or_services_not_received",
                      "reason_description":"Goods not received",
                      "evidence":{"shipping_proof":["doc_private"]}
                    }}
                  }
                }
                """;
        when(eventRepository.saveAndFlush(any(RazorpayWebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        new RazorpayWebhookService(eventRepository, objectMapper, processor)
                .storeEvent("evt_123", payload);

        ArgumentCaptor<RazorpayWebhookEvent> captor =
                ArgumentCaptor.forClass(RazorpayWebhookEvent.class);
        verify(eventRepository).saveAndFlush(captor.capture());
        RazorpayWebhookEvent saved = captor.getValue();
        JsonNode stored = objectMapper.readTree(saved.getRawPayload());

        assertEquals("pay_123", stored.path("payload").path("payment").path("entity").path("id").asText());
        assertEquals("order_123", stored.path("payload").path("payment").path("entity").path("order_id").asText());
        assertEquals("disp_123", stored.path("payload").path("dispute").path("entity").path("id").asText());
        assertEquals("goods_or_services_not_received",
                stored.path("payload").path("dispute").path("entity").path("reason_code").asText());
        assertFalse(saved.getRawPayload().contains("customer@example.com"));
        assertFalse(saved.getRawPayload().contains("+919999999999"));
        assertFalse(saved.getRawPayload().contains("customer@upi"));
        assertFalse(saved.getRawPayload().contains("doc_private"));
    }

    @Test
    void rejectsMalformedWebhookJson() {
        assertThrows(IllegalArgumentException.class, () ->
                new RazorpayWebhookService(eventRepository, objectMapper, processor)
                        .storeEvent("evt_bad", "{invalid"));
        verifyNoInteractions(eventRepository);
    }
}

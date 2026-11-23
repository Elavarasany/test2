package nmk_backend.service;

import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookReviewServiceTest {

    @Mock
    private RazorpayWebhookEventRepository repository;

    @Test
    void reviewQueueExposesRefundIdentifiersButNotRawPayload() {
        RazorpayWebhookEvent event = new RazorpayWebhookEvent();
        event.setEventId("evt_123");
        event.setEventType("refund.processed");
        event.setStatus("MANUAL_REVIEW");
        event.setAttemptCount(1);
        event.setProcessingError("Requires provider reconciliation");
        event.setRawPayload("""
                {
                  "event":"refund.processed",
                  "payload":{"refund":{"entity":{
                    "id":"rfnd_123",
                    "payment_id":"pay_123",
                    "amount":5000,
                    "currency":"INR",
                    "status":"processed"
                  }}}
                }
                """);

        when(repository.findByStatusOrderByReceivedAtAsc(
                eq("MANUAL_REVIEW"), any(PageRequest.class)
        )).thenReturn(new PageImpl<>(List.of(event)));

        var result = new RazorpayWebhookReviewService(
                repository, new ObjectMapper()
        ).getManualReviewQueue(0, 25);

        assertEquals(1, result.getTotalElements());
        var item = result.getContent().get(0);
        assertEquals("evt_123", item.eventId());
        assertEquals("refund.processed", item.eventType());
        assertEquals("rfnd_123", item.resourceId());
        assertEquals("pay_123", item.paymentId());
        assertEquals(5000L, item.amountPaise());
        assertEquals("INR", item.currency());
        assertEquals("processed", item.providerStatus());
        assertFalse(item.toString().contains("rawPayload"));
        verify(repository).findByStatusOrderByReceivedAtAsc(
                eq("MANUAL_REVIEW"), any(PageRequest.class)
        );
    }

    @Test
    void malformedPayloadRemainsVisibleWithoutRawJson() {
        RazorpayWebhookEvent event = new RazorpayWebhookEvent();
        event.setEventId("evt_bad");
        event.setEventType("refund.processed");
        event.setStatus("MANUAL_REVIEW");
        event.setRawPayload("not-json");

        when(repository.findByStatusOrderByReceivedAtAsc(
                eq("MANUAL_REVIEW"), any(PageRequest.class)
        )).thenReturn(new PageImpl<>(List.of(event)));

        var item = new RazorpayWebhookReviewService(
                repository, new ObjectMapper()
        ).getManualReviewQueue(0, 25).getContent().get(0);

        assertEquals("evt_bad", item.eventId());
        assertNull(item.resourceId());
        assertNull(item.paymentId());
        assertNull(item.amountPaise());
    }

    @Test
    void invalidPaginationIsRejected() {
        var service = new RazorpayWebhookReviewService(
                repository, new ObjectMapper()
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.getManualReviewQueue(-1, 25));
        assertThrows(IllegalArgumentException.class,
                () -> service.getManualReviewQueue(0, 101));
        verifyNoInteractions(repository);
    }
}

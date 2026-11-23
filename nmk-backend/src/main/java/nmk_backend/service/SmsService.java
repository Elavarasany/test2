
package nmk_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SmsService {

    @Value("${sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${sms.auth-key:TEST_AUTH_KEY}")
    private String authKey;

    @Value("${sms.sender-id:NMKOPT}")
    private String senderId;

    @Value("${sms.flow-id:TEST_FLOW_ID}")
    private String flowId;

    public void sendOrderNotification(
            String phone,
            String orderId,
            String status,
            String courierName,
            String trackingNumber,
            String trackingUrl) {

        if (!smsEnabled) {
            // Deliberately do not log customer contact or tracking details.
            return;
        }

        // Real MSG91 integration will be enabled later.
        throw new IllegalStateException(
                "MSG91 credentials and live integration are not configured"
        );
    }
}

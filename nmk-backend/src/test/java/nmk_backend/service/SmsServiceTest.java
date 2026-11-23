package nmk_backend.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmsServiceTest {

    @Test
    void testModeDoesNotPrintCustomerOrTrackingInformation() throws Exception {
        SmsService service = new SmsService();
        setField(service, "smsEnabled", false);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            service.sendOrderNotification(
                    "+919876543210",
                    "order-123",
                    "SHIPPED",
                    "Private Courier",
                    "TRACK-SECRET-123",
                    "https://example.test/track/secret-token"
            );
        } finally {
            System.setOut(originalOut);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void enabledSmsStillFailsUntilProviderIntegrationIsConfigured() throws Exception {
        SmsService service = new SmsService();
        setField(service, "smsEnabled", true);

        assertThrows(IllegalStateException.class, () ->
                service.sendOrderNotification(
                        "+919876543210",
                        "order-123",
                        "SHIPPED",
                        "Courier",
                        "TRACK-123",
                        "https://example.test/track/token"
                )
        );
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

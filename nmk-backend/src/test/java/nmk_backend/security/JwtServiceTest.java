package nmk_backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    @Test
    void startupValidationAcceptsAKeyWithAtLeast256Bits() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(
                service,
                "secret",
                "test-only-secret-with-more-than-thirty-two-bytes-for-hmac"
        );

        assertDoesNotThrow(service::validateSigningKeyAtStartup);
    }

    @Test
    void startupValidationRejectsAWeakSigningKey() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secret", "too-short");

        assertThrows(RuntimeException.class, service::validateSigningKeyAtStartup);
    }
}

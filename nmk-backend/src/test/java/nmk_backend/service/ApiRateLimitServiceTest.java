package nmk_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiRateLimitServiceTest {
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void incrementsSharedBucketAndRejectsRequestsOverLimit() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(6);
        ApiRateLimitService service = new ApiRateLimitService(jdbcTemplate, true);

        var decision = service.increment("admin-login", "192.0.2.1", 5, 60);

        assertFalse(decision.allowed());
        assertEquals(6, decision.currentCount());
        assertEquals(5, decision.limit());
        assertTrue(decision.retryAfterSeconds() >= 1);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        assertEquals(3, arguments.getValue().length);
        assertEquals(64, ((String) arguments.getValue()[0]).length());
    }

    @Test
    void allowsRequestsWithinConfiguredLimit() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(3);
        ApiRateLimitService service = new ApiRateLimitService(jdbcTemplate, true);

        var decision = service.increment("checkout", "192.0.2.2", 10, 60);

        assertTrue(decision.allowed());
        assertEquals(3, decision.currentCount());
    }

    @Test
    void rejectsInvalidLimitConfiguration() {
        ApiRateLimitService service = new ApiRateLimitService(jdbcTemplate, true);
        assertThrows(IllegalArgumentException.class,
                () -> service.increment("login", "192.0.2.1", 0, 60));
        verifyNoInteractions(jdbcTemplate);
    }
}

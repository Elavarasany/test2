package nmk_backend.security;

import nmk_backend.config.SecurityConfig;
import nmk_backend.service.AdminAuditEventService;
import nmk_backend.security.ApiRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CorsConfigurationTest {

    @Test
    void configuresOnlyExplicitOriginsAndExpectedMethods() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class), new AdminSecurityRejectionAuditFilter(mock(AdminAuditEventService.class)), mock(ApiRateLimitFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins",
                "https://shop.example.com, https://admin.example.com");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", true);

        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/products"));

        assertNotNull(cors);
        assertEquals(
                java.util.List.of("https://shop.example.com", "https://admin.example.com"),
                cors.getAllowedOrigins());
        assertEquals(
                java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
                cors.getAllowedMethods());
        assertTrue(cors.getAllowCredentials());
    }

    @Test
    void rejectsWildcardOrigins() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class), new AdminSecurityRejectionAuditFilter(mock(AdminAuditEventService.class)), mock(ApiRateLimitFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "*");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", false);

        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }

    @Test
    void rejectsNonHttpsOriginsWhenProductionPolicyIsEnabled() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class), new AdminSecurityRejectionAuditFilter(mock(AdminAuditEventService.class)), mock(ApiRateLimitFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", true);

        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }
    @Test
    void rejectsWildcardSubdomainsAndNonOriginUrlComponents() {
        for (String value : java.util.List.of(
                "https://*.example.com",
                "https://shop.example.com/path",
                "https://shop.example.com?next=/admin",
                "https://user@shop.example.com",
                "ftp://shop.example.com",
                "https://shop.example.com#fragment"
        )) {
            SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class), new AdminSecurityRejectionAuditFilter(mock(AdminAuditEventService.class)), mock(ApiRateLimitFilter.class));
            ReflectionTestUtils.setField(config, "allowedOrigins", value);
            ReflectionTestUtils.setField(config, "requireHttpsOrigins", false);

            assertThrows(IllegalStateException.class, config::corsConfigurationSource, value);
        }
    }

    @Test
    void acceptsExplicitHttpOriginForLocalDevelopment() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class), new AdminSecurityRejectionAuditFilter(mock(AdminAuditEventService.class)), mock(ApiRateLimitFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", false);

        assertDoesNotThrow(config::corsConfigurationSource);
    }

}


package nmk_backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private AdminRepository adminRepository;
    private JwtAuthenticationFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        adminRepository = mock(AdminRepository.class);

        filter = new JwtAuthenticationFilter(
                jwtService,
                adminRepository
        );

        filterChain = mock(FilterChain.class);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenForExistingAdminGetsAdminRole()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/admin/products");

        request.setServletPath("/api/admin/products");
        request.addHeader("Authorization", "Bearer valid-token");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(jwtService.extractUsername("valid-token"))
                .thenReturn("admin");

        when(jwtService.isTokenValid("valid-token", "admin"))
                .thenReturn(true);

        Admin admin = new Admin();
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        when(adminRepository.findByUsernameIgnoreCase("admin"))
                .thenReturn(Optional.of(admin));

        filter.doFilter(request, response, filterChain);

        var authentication =
                SecurityContextHolder.getContext().getAuthentication();

        assertNotNull(authentication);
        assertEquals("admin", authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_ADMIN")));

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void validTokenForNonexistentAdminIsNotAuthenticated()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/admin/products");

        request.setServletPath("/api/admin/products");
        request.addHeader("Authorization", "Bearer valid-token");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(jwtService.extractUsername("valid-token"))
                .thenReturn("deleted-admin");

        when(jwtService.isTokenValid("valid-token", "deleted-admin"))
                .thenReturn(true);

        when(adminRepository.findByUsernameIgnoreCase("deleted-admin"))
                .thenReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("no longer exists"));
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void invalidTokenReturnsUnauthorized()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/admin/products");

        request.setServletPath("/api/admin/products");
        request.addHeader("Authorization", "Bearer invalid-token");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(jwtService.extractUsername("invalid-token"))
                .thenThrow(new io.jsonwebtoken.JwtException("Invalid token"));

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("Invalid or expired token"));

        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void missingTokenDoesNotAuthenticateRequest()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/admin/products");

        request.setServletPath("/api/admin/products");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }
}

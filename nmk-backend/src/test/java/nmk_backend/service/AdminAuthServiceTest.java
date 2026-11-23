package nmk_backend.service;

import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import nmk_backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AdminAuthServiceTest {

    @Test
    void loginRejectsPasswordOverBcryptUtf8ByteLimitBeforeLookup() {
        AdminRepository repository = mock(AdminRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AdminAuthService service = new AdminAuthService(repository, encoder, jwtService);

        assertThrows(IllegalArgumentException.class,
                () -> service.login("admin", "😀".repeat(19)));

        verifyNoInteractions(repository, encoder, jwtService);
    }

    @Test
    void passwordVerificationRejectsPasswordOverBcryptUtf8ByteLimit() {
        AdminRepository repository = mock(AdminRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AdminAuthService service = new AdminAuthService(repository, encoder, jwtService);

        assertThrows(IllegalArgumentException.class,
                () -> service.verifyPassword("admin", "😀".repeat(19)));

        verifyNoInteractions(repository, encoder, jwtService);
    }

    @Test
    void loginContinuesForPasswordWithinBcryptUtf8ByteLimit() {
        AdminRepository repository = mock(AdminRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AdminAuthService service = new AdminAuthService(repository, encoder, jwtService);
        Admin admin = new Admin();
        admin.setUsername("admin");
        admin.setPassword("encoded");
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("valid-password", "encoded")).thenReturn(true);
        when(jwtService.generateToken("admin")).thenReturn("jwt");

        service.login("admin", "valid-password");

        verify(encoder).matches("valid-password", "encoded");
        verify(jwtService).generateToken("admin");
    }
}

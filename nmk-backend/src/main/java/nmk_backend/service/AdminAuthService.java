package nmk_backend.service;

import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import nmk_backend.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.nio.charset.StandardCharsets;

@Service
public class AdminAuthService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public String login(String username, String password) {
        validatePasswordLength(password);
        Admin admin = adminRepository.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new RuntimeException("Invalid username or password"));

        if (!admin.isEnabled() || !passwordEncoder.matches(password, admin.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        return jwtService.generateToken(admin.getUsername());
    }

    public String getRole(String username) {
        return adminRepository.findByUsernameIgnoreCase(username.trim())
                .filter(Admin::isEnabled)
                .map(Admin::getRole)
                .orElseThrow(() -> new RuntimeException("Staff account is disabled or missing"));
    }

    public void verifyPassword(String username, String password) {
        validatePasswordLength(password);
        Admin admin = adminRepository.findByUsernameIgnoreCase(username.trim())
                .filter(Admin::isEnabled)
                .orElseThrow(() -> new RuntimeException("Invalid admin"));

        if (!passwordEncoder.matches(password, admin.getPassword())) {
            throw new RuntimeException("Incorrect password");
        }
    }
    private void validatePasswordLength(String password) {
        if (password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Invalid username or password");
        }
    }
}

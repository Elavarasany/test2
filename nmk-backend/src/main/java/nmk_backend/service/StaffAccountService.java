package nmk_backend.service;

import nmk_backend.dto.StaffAccountCreateRequest;
import nmk_backend.dto.StaffAccountResponse;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Service
public class StaffAccountService {

    private static final String ADMIN = "ADMIN";
    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;

    public StaffAccountService(AdminRepository adminRepository, PasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<StaffAccountResponse> list() {
        return adminRepository.findAll().stream()
                .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()))
                .map(StaffAccountResponse::from)
                .toList();
    }

    @Transactional
    public StaffAccountResponse create(StaffAccountCreateRequest request) {
        String username = normalizeUsername(request.username());
        String role = normalizeRole(request.role());

        if (adminRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("That username is already in use");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must not exceed 72 UTF-8 bytes");
        }

        Admin account = new Admin();
        account.setUsername(username);
        account.setPassword(passwordEncoder.encode(request.password()));
        account.setRole(role);
        account.setEnabled(true);
        return StaffAccountResponse.from(adminRepository.save(account));
    }

    @Transactional
    public StaffAccountResponse setEnabled(Long id, boolean enabled, String actorUsername) {
        List<Admin> lockedAccounts = adminRepository.findAllForUpdate();
        Admin account = findLockedAccount(lockedAccounts, id);
        preventSelfLockout(account, actorUsername, enabled ? null : "disable");

        long activeAdmins = lockedAccounts.stream()
                .filter(Admin::isEnabled)
                .filter(item -> ADMIN.equals(item.getRole()))
                .count();
        if (!enabled && ADMIN.equals(account.getRole()) && activeAdmins <= 1) {
            throw new IllegalArgumentException("The last active administrator cannot be disabled");
        }

        account.setEnabled(enabled);
        return StaffAccountResponse.from(adminRepository.save(account));
    }

    @Transactional
    public StaffAccountResponse setRole(Long id, String requestedRole, String actorUsername) {
        List<Admin> lockedAccounts = adminRepository.findAllForUpdate();
        Admin account = findLockedAccount(lockedAccounts, id);
        String role = normalizeRole(requestedRole);

        long activeAdmins = lockedAccounts.stream()
                .filter(Admin::isEnabled)
                .filter(item -> ADMIN.equals(item.getRole()))
                .count();
        if (ADMIN.equals(account.getRole()) && !ADMIN.equals(role)) {
            preventSelfLockout(account, actorUsername, "change the role of");
            if (account.isEnabled() && activeAdmins <= 1) {
                throw new IllegalArgumentException("The last active administrator cannot be demoted");
            }
        }

        account.setRole(role);
        return StaffAccountResponse.from(adminRepository.save(account));
    }

    private Admin findLockedAccount(List<Admin> lockedAccounts, Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("A valid staff account id is required");
        }
        return lockedAccounts.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Staff account not found"));
    }

    private void preventSelfLockout(Admin account, String actorUsername, String action) {
        if (action != null && account.getUsername().equalsIgnoreCase(actorUsername)) {
            throw new IllegalArgumentException("You cannot " + action + " your own account");
        }
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ADMIN", "MANAGER", "CASHIER").contains(normalized)) {
            throw new IllegalArgumentException("Role must be ADMIN, MANAGER or CASHIER");
        }
        return normalized;
    }
}
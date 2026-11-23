
package nmk_backend.service;

import nmk_backend.entity.Admin;
import nmk_backend.entity.AdminActionAuthorization;
import nmk_backend.repository.AdminActionAuthorizationRepository;
import nmk_backend.repository.AdminRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

@Service
public class AdminActionAuthorizationService {

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "CLEAR_ORDERS",
            "CLEAR_APPOINTMENTS",
            "RAZORPAY_REFUND",
            "INVENTORY_RECEIPT",
            "INVENTORY_ADJUSTMENT", "POS_RETURN", "POS_VOID", "POS_EXCHANGE", "STOCK_COUNT_POST", "STOCK_COUNT_CANCEL", "GOODS_RECEIPT", "SUPPLIER_RETURN", "PURCHASE_ORDER_SUBMIT", "SUPPLIER_BILL_POST", "SUPPLIER_PAYMENT", "SUPPLIER_CREDIT_NOTE"
    );

    private static final Set<String> MANAGER_ALLOWED_ACTIONS = Set.of(
            "INVENTORY_RECEIPT", "INVENTORY_ADJUSTMENT", "POS_RETURN", "POS_VOID", "POS_EXCHANGE", "STOCK_COUNT_POST", "STOCK_COUNT_CANCEL", "GOODS_RECEIPT", "SUPPLIER_RETURN", "PURCHASE_ORDER_SUBMIT"
    );

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AdminRepository adminRepository;
    private final AdminActionAuthorizationRepository authorizationRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminActionAuthorizationService(
            AdminRepository adminRepository,
            AdminActionAuthorizationRepository authorizationRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.adminRepository = adminRepository;
        this.authorizationRepository = authorizationRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public String authorizeAction(
            String username,
            String password,
            String action
    ) {
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Invalid destructive action");
        }
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Incorrect admin password");
        }

        Admin admin = adminRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Invalid staff account"));
        if (!admin.isEnabled()) throw new IllegalArgumentException("Staff account is disabled");
        String role = admin.getRole() == null ? "" : admin.getRole().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ADMIN".equals(role) && !("MANAGER".equals(role) && MANAGER_ALLOWED_ACTIONS.contains(action))) {
            throw new IllegalArgumentException("This staff role cannot authorize the requested action");
        }

        if (!passwordEncoder.matches(password, admin.getPassword())) {
            throw new IllegalArgumentException("Incorrect admin password");
        }

        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);

        String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        AdminActionAuthorization authorization =
                new AdminActionAuthorization();

        authorization.setTokenHash(hashToken(rawToken));
        authorization.setAdminUsername(username);
        authorization.setAction(action);
        authorization.setCreatedAt(LocalDateTime.now());
        authorization.setExpiresAt(LocalDateTime.now().plusMinutes(2));

        authorizationRepository.save(authorization);

        return rawToken;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeAuthorization(
            String username,
            String rawToken,
            String expectedAction
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Fresh password authorization is required"
            );
        }

        if (!ALLOWED_ACTIONS.contains(expectedAction)) {
            throw new IllegalArgumentException("Invalid destructive action");
        }

        String tokenHash = hashToken(rawToken);

        AdminActionAuthorization authorization =
                authorizationRepository.findByTokenHashForUpdate(tokenHash)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Invalid or expired authorization"
                                ));

        LocalDateTime now = LocalDateTime.now();

        Admin currentAdmin = adminRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Staff account is no longer available"));
        String currentRole = currentAdmin.getRole() == null
                ? ""
                : currentAdmin.getRole().trim().toUpperCase(java.util.Locale.ROOT);
        if (!currentAdmin.isEnabled()
                || (!"ADMIN".equals(currentRole)
                && !("MANAGER".equals(currentRole) && MANAGER_ALLOWED_ACTIONS.contains(expectedAction)))) {
            throw new IllegalArgumentException("Staff account is no longer authorized for this action");
        }

        if (!authorization.getAdminUsername().equals(username)
                || !authorization.getAction().equals(expectedAction)
                || authorization.getConsumedAt() != null
                || !authorization.getExpiresAt().isAfter(now)) {

            throw new IllegalArgumentException(
                    "Authorization is invalid, expired, or already used"
            );
        }

        authorization.setConsumedAt(now);
        authorizationRepository.save(authorization);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    token.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to secure authorization token",
                    exception
            );
        }
    }
}

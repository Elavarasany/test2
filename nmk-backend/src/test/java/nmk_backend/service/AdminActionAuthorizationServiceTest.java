
package nmk_backend.service;

import nmk_backend.entity.Admin;
import nmk_backend.entity.AdminActionAuthorization;
import nmk_backend.repository.AdminActionAuthorizationRepository;
import nmk_backend.repository.AdminRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminActionAuthorizationServiceTest {

    @Mock
    private AdminRepository adminRepository;

    @Mock
    private AdminActionAuthorizationRepository authorizationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminActionAuthorizationService service;

    private Admin admin;

    @BeforeEach
    void setUp() {
        admin = new Admin();
        admin.setUsername("admin");
        admin.setPassword("encoded-password");
    }

    @Test
    void authorizeActionCreatesHashedShortLivedToken() {
        when(adminRepository.findByUsername("admin"))
                .thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("correct-password", "encoded-password"))
                .thenReturn(true);

        String token = service.authorizeAction(
                "admin", "correct-password", "CLEAR_ORDERS"
        );

        assertNotNull(token);
        assertFalse(token.isBlank());

        ArgumentCaptor<AdminActionAuthorization> captor =
                ArgumentCaptor.forClass(AdminActionAuthorization.class);

        verify(authorizationRepository).save(captor.capture());

        AdminActionAuthorization saved = captor.getValue();

        assertNotEquals(token, saved.getTokenHash());
        assertEquals(64, saved.getTokenHash().length());
        assertEquals("admin", saved.getAdminUsername());
        assertEquals("CLEAR_ORDERS", saved.getAction());
        assertNotNull(saved.getCreatedAt());
        assertTrue(saved.getExpiresAt().isAfter(saved.getCreatedAt()));
        assertTrue(
                saved.getExpiresAt().isBefore(
                        saved.getCreatedAt().plusMinutes(3)
                )
        );
    }

    @Test
    void authorizeActionAcceptsBothInventoryActions() {
        when(adminRepository.findByUsername("admin"))
                .thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("correct-password", "encoded-password"))
                .thenReturn(true);

        for (String action : new String[]{
                "INVENTORY_RECEIPT",
                "INVENTORY_ADJUSTMENT"
        }) {
            String token = service.authorizeAction(
                    "admin", "correct-password", action
            );

            assertNotNull(token);
            assertFalse(token.isBlank());
        }

        ArgumentCaptor<AdminActionAuthorization> captor =
                ArgumentCaptor.forClass(AdminActionAuthorization.class);

        verify(authorizationRepository, times(2)).save(captor.capture());

        assertEquals(
                java.util.Set.of("INVENTORY_RECEIPT", "INVENTORY_ADJUSTMENT"),
                captor.getAllValues().stream()
                        .map(AdminActionAuthorization::getAction)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void managerCanAuthorizeFinancialPosActionsButNotGlobalClearActions() {
        admin.setRole("MANAGER");
        when(adminRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("correct-password", "encoded-password")).thenReturn(true);
        for (String action : new String[]{"POS_RETURN", "POS_VOID", "POS_EXCHANGE", "INVENTORY_RECEIPT", "INVENTORY_ADJUSTMENT", "STOCK_COUNT_POST", "STOCK_COUNT_CANCEL", "GOODS_RECEIPT", "SUPPLIER_RETURN", "PURCHASE_ORDER_SUBMIT"}) {
            assertNotNull(service.authorizeAction("admin", "correct-password", action));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeAction("admin", "correct-password", "CLEAR_ORDERS"));
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeAction("admin", "correct-password", "SUPPLIER_BILL_POST"));
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeAction("admin", "correct-password", "SUPPLIER_PAYMENT"));
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeAction("admin", "correct-password", "SUPPLIER_CREDIT_NOTE"));
    }

    @Test
    void disabledStaffCannotAuthorizeActions() {
        admin.setEnabled(false);
        when(adminRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeAction("admin", "correct-password", "POS_EXCHANGE"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void authorizeActionRejectsUnknownAction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.authorizeAction(
                        "admin", "password", "UNKNOWN_ACTION"
                )
        );

        verifyNoInteractions(adminRepository, passwordEncoder);
        verifyNoInteractions(authorizationRepository);
    }

    @Test
    void authorizeActionRejectsIncorrectPassword() {
        when(adminRepository.findByUsername("admin"))
                .thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("wrong-password", "encoded-password"))
                .thenReturn(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.authorizeAction(
                        "admin", "wrong-password", "CLEAR_ORDERS"
                )
        );

        verify(authorizationRepository, never()).save(any());
    }

    @Test
    void consumeAuthorizationRejectsMissingToken() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorization(
                        "admin", null, "CLEAR_ORDERS"
                )
        );

        verifyNoInteractions(authorizationRepository);
    }

    @Test
    void consumeAuthorizationRejectsStaffDisabledAfterTokenWasIssued() {
        AdminActionAuthorization authorization = validAuthorization();
        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));
        when(adminRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        admin.setEnabled(false);

        assertThrows(IllegalArgumentException.class,
                () -> service.consumeAuthorization("admin", "raw-token", "CLEAR_ORDERS"));
        assertNull(authorization.getConsumedAt());
        verify(authorizationRepository, never()).save(authorization);
    }

    @Test
    void consumeAuthorizationRejectsManagerDemotedAfterTokenWasIssued() {
        AdminActionAuthorization authorization = validAuthorization();
        authorization.setAction("POS_RETURN");
        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));
        when(adminRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        admin.setRole("CASHIER");

        assertThrows(IllegalArgumentException.class,
                () -> service.consumeAuthorization("admin", "raw-token", "POS_RETURN"));
        assertNull(authorization.getConsumedAt());
        verify(authorizationRepository, never()).save(authorization);
    }

    @Test
    void consumeAuthorizationRejectsWrongAdmin() {
        AdminActionAuthorization authorization = validAuthorization();

        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorization(
                        "another-admin", "raw-token", "CLEAR_ORDERS"
                )
        );

        assertNull(authorization.getConsumedAt());
    }

    @Test
    void consumeAuthorizationRejectsWrongAction() {
        AdminActionAuthorization authorization = validAuthorization();

        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorization(
                        "admin", "raw-token", "CLEAR_APPOINTMENTS"
                )
        );

        assertNull(authorization.getConsumedAt());
    }

    @Test
    void consumeAuthorizationRejectsInventoryTokenForDifferentAction() {
        AdminActionAuthorization authorization = validAuthorization();
        authorization.setAction("INVENTORY_RECEIPT");

        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorization(
                        "admin", "raw-token", "INVENTORY_ADJUSTMENT"
                )
        );

        assertNull(authorization.getConsumedAt());
        verify(authorizationRepository, never()).save(any());
    }

    @Test
    void consumeAuthorizationRejectsExpiredToken() {
        AdminActionAuthorization authorization = validAuthorization();
        authorization.setExpiresAt(LocalDateTime.now().minusSeconds(1));

        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorization(
                        "admin", "raw-token", "CLEAR_ORDERS"
                )
        );

        assertNull(authorization.getConsumedAt());
    }

    @Test
    void consumeAuthorizationRejectsAlreadyConsumedToken() {
        AdminActionAuthorization authorization = validAuthorization();
        authorization.setConsumedAt(LocalDateTime.now().minusSeconds(5));

        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorization(
                        "admin", "raw-token", "CLEAR_ORDERS"
                )
        );
    }

    @Test
    void consumeAuthorizationMarksValidTokenAsConsumed() {
        AdminActionAuthorization authorization = validAuthorization();
        admin.setRole("ADMIN");
        admin.setEnabled(true);

        when(adminRepository.findByUsername("admin"))
                .thenReturn(Optional.of(admin));
        when(authorizationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(authorization));

        service.consumeAuthorization(
                "admin", "raw-token", "CLEAR_ORDERS"
        );

        assertNotNull(authorization.getConsumedAt());
        verify(authorizationRepository).save(authorization);
    }

    @Test
    void authorizeActionRejectsPasswordsOverBcryptByteLimitBeforeDatabaseAccess() {
        String oversizedUtf8Password = "😀".repeat(19);

        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeAction("admin", oversizedUtf8Password, "POS_RETURN"));

        verifyNoInteractions(adminRepository, passwordEncoder, authorizationRepository);
    }

    private AdminActionAuthorization validAuthorization() {
        AdminActionAuthorization authorization =
                new AdminActionAuthorization();

        authorization.setTokenHash(
                "03f4a7c5b2d1e8f6091234567890abcdef1234567890abcdef1234567890abcd"
        );
        authorization.setAdminUsername("admin");
        authorization.setAction("CLEAR_ORDERS");
        authorization.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        authorization.setExpiresAt(LocalDateTime.now().plusMinutes(2));

        return authorization;
    }
}

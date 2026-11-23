package nmk_backend.service;

import nmk_backend.dto.StaffAccountCreateRequest;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaffAccountServiceTest {

    private AdminRepository repository;
    private PasswordEncoder encoder;
    private StaffAccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new StaffAccountService(repository, encoder);
        when(repository.save(any(Admin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(encoder.encode("StrongPass!2026")).thenReturn("bcrypt-hash");
    }

    @Test
    void createsNormalizedCashierAndNeverReturnsPasswordHash() {
        when(repository.existsByUsernameIgnoreCase("cashier.one")).thenReturn(false);

        var response = service.create(new StaffAccountCreateRequest(
                " Cashier.One ", "StrongPass!2026", "cashier"
        ));

        assertEquals("cashier.one", response.username());
        assertEquals("CASHIER", response.role());
        assertTrue(response.enabled());
        verify(encoder).encode("StrongPass!2026");
        verify(repository).save(argThat(account ->
                "bcrypt-hash".equals(account.getPassword())
                        && "CASHIER".equals(account.getRole())
                        && account.isEnabled()
        ));
    }

    @Test
    void rejectsDuplicateUsernameIgnoringCase() {
        when(repository.existsByUsernameIgnoreCase("cashier.one")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
                service.create(new StaffAccountCreateRequest(
                        "Cashier.One", "StrongPass!2026", "CASHIER"
                ))
        );
        verify(repository, never()).save(any());
    }

    @Test
    void cannotDisableTheLastActiveAdministrator() {
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("owner");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        when(repository.findAllForUpdate()).thenReturn(java.util.List.of(admin));

        assertThrows(IllegalArgumentException.class, () ->
                service.setEnabled(1L, false, "manager")
        );
        verify(repository, never()).save(any());
    }

    @Test
    void staffMemberCannotDisableOwnAccount() {
        Admin cashier = new Admin();
        cashier.setId(2L);
        cashier.setUsername("cashier.one");
        cashier.setRole("CASHIER");
        when(repository.findAllForUpdate()).thenReturn(java.util.List.of(cashier));

        assertThrows(IllegalArgumentException.class, () ->
                service.setEnabled(2L, false, "CASHIER.ONE")
        );
        verify(repository, never()).save(any());
    }
}
package nmk_backend.service;

import nmk_backend.entity.Admin;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.InventoryLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminLocationAccessServiceTest {
    private AdminRepository adminRepository;
    private InventoryLocationRepository locationRepository;
    private AdminLocationAccessService service;

    @BeforeEach
    void setUp() {
        adminRepository = mock(AdminRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        service = new AdminLocationAccessService(adminRepository, locationRepository);
    }

    @Test
    void administratorHasGlobalLocationAccess() {
        service.assertCanAccess("root", "ADMIN", 99L);
        verifyNoInteractions(adminRepository);
    }

    @Test
    void assignedCashierCanAccessAssignedLocationOnly() {
        InventoryLocation location = new InventoryLocation();
        location.setId(7L);
        Admin cashier = new Admin();
        cashier.setUsername("cashier");
        cashier.setRole("CASHIER");
        cashier.setEnabled(true);
        cashier.setAssignedLocations(Set.of(location));
        when(adminRepository.findWithLocationsByUsername("cashier")).thenReturn(Optional.of(cashier));

        assertDoesNotThrow(() -> service.assertCanAccess("cashier", "CASHIER", 7L));
        assertThrows(IllegalArgumentException.class,
                () -> service.assertCanAccess("cashier", "CASHIER", 8L));
    }

    @Test
    void assignedButDeactivatedLocationIsDenied() {
        InventoryLocation location = new InventoryLocation();
        location.setId(9L);
        location.setActive(false);
        Admin cashier = new Admin();
        cashier.setUsername("cashier");
        cashier.setRole("CASHIER");
        cashier.setEnabled(true);
        cashier.setAssignedLocations(Set.of(location));
        when(adminRepository.findWithLocationsByUsername("cashier")).thenReturn(Optional.of(cashier));

        assertThrows(IllegalArgumentException.class,
                () -> service.assertCanAccess("cashier", "CASHIER", 9L));
    }

    @Test
    void unassignedOrDisabledStaffAreDenied() {
        Admin staff = new Admin();
        staff.setUsername("staff");
        staff.setRole("MANAGER");
        staff.setEnabled(false);
        when(adminRepository.findWithLocationsByUsername("staff")).thenReturn(Optional.of(staff));

        assertThrows(IllegalArgumentException.class,
                () -> service.assertCanAccess("staff", "MANAGER", 7L));
    }

    @Test
    void assignmentReplacementRejectsMissingInactiveAndDuplicateLocations() {
        Admin staff = new Admin();
        staff.setRole("CASHIER");
        when(adminRepository.findById(3L)).thenReturn(Optional.of(staff));

        assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(3L, List.of(1L, 1L)));

        InventoryLocation inactive = new InventoryLocation();
        inactive.setId(1L);
        inactive.setActive(false);
        when(locationRepository.findAllById(List.of(1L))).thenReturn(List.of(inactive));
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(3L, List.of(1L)));
    }

    @Test
    void assignmentReplacementPersistsOnlyActiveKnownLocations() {
        Admin staff = new Admin();
        staff.setRole("CASHIER");
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setActive(true);
        when(adminRepository.findById(3L)).thenReturn(Optional.of(staff));
        when(locationRepository.findAllById(List.of(1L))).thenReturn(List.of(location));
        when(adminRepository.save(staff)).thenReturn(staff);

        assertEquals(List.of(1L), service.replaceAssignments(3L, List.of(1L)));
        assertEquals(Set.of(location), staff.getAssignedLocations());
        verify(adminRepository).save(staff);
    }
}
package nmk_backend.service;

import nmk_backend.entity.Admin;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.InventoryLocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminLocationAccessService {
    private final AdminRepository adminRepository;
    private final InventoryLocationRepository locationRepository;

    public AdminLocationAccessService(AdminRepository adminRepository,
                                      InventoryLocationRepository locationRepository) {
        this.adminRepository = adminRepository;
        this.locationRepository = locationRepository;
    }

    @Transactional(readOnly = true)
    public void assertCanAccess(String username, String role, Long locationId) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid location is required");
        }
        if ("ADMIN".equals(normalizeRole(role))) return;
        Admin staff = adminRepository.findWithLocationsByUsername(username)
                .filter(Admin::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Staff account is disabled or missing"));
        boolean assigned = staff.getAssignedLocations().stream()
                .anyMatch(location -> location.getId().equals(locationId) && location.isActive());
        if (!assigned) {
            throw new IllegalArgumentException("You are not assigned to this inventory location");
        }
    }

    @Transactional(readOnly = true)
    public List<Long> getAccessibleLocationIds(String username, String role) {
        if ("ADMIN".equals(normalizeRole(role))) {
            return locationRepository.findByActiveTrue().stream().map(InventoryLocation::getId).sorted().toList();
        }
        Admin staff = adminRepository.findWithLocationsByUsername(username)
                .filter(Admin::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Staff account is disabled or missing"));
        return staff.getAssignedLocations().stream().filter(InventoryLocation::isActive)
                .map(InventoryLocation::getId).sorted().toList();
    }

    @Transactional(readOnly = true)
    public List<Long> getAssignedLocationIds(Long staffId) {
        Admin staff = adminRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff account was not found"));
        return staff.getAssignedLocations().stream()
                .map(InventoryLocation::getId).sorted().toList();
    }

    @Transactional
    public List<Long> replaceAssignments(Long staffId, List<Long> locationIds) {
        if (locationIds == null || locationIds.size() > 100
                || locationIds.stream().anyMatch(id -> id == null || id <= 0)
                || Set.copyOf(locationIds).size() != locationIds.size()) {
            throw new IllegalArgumentException("Location IDs must be unique positive values");
        }
        Admin staff = adminRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff account was not found"));
        if ("ADMIN".equals(normalizeRole(staff.getRole())) && !locationIds.isEmpty()) {
            throw new IllegalArgumentException("Administrator accounts have global location access and do not need assignments");
        }
        List<InventoryLocation> locations = locationRepository.findAllById(locationIds);
        if (locations.size() != locationIds.size()) {
            throw new IllegalArgumentException("One or more inventory locations were not found");
        }
        if (locations.stream().anyMatch(location -> !location.isActive())) {
            throw new IllegalArgumentException("Inactive locations cannot be assigned to staff");
        }
        staff.setAssignedLocations(locations.stream().collect(Collectors.toSet()));
        adminRepository.save(staff);
        return getAssignedLocationIds(staffId);
    }

    private static String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }
}
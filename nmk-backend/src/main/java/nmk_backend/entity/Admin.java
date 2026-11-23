package nmk_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "admins")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) not null default 'ADMIN'")
    private String role = "ADMIN";

    @Column(nullable = false, columnDefinition = "boolean not null default true")
    private boolean enabled = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "admin_location_assignments",
            joinColumns = @JoinColumn(name = "admin_id"),
            inverseJoinColumns = @JoinColumn(name = "location_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_admin_location_assignment", columnNames = {"admin_id", "location_id"}))
    private Set<InventoryLocation> assignedLocations = new LinkedHashSet<>();

    public Admin() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<InventoryLocation> getAssignedLocations() { return assignedLocations; }
    public void setAssignedLocations(Set<InventoryLocation> assignedLocations) { this.assignedLocations = assignedLocations == null ? new LinkedHashSet<>() : new LinkedHashSet<>(assignedLocations); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
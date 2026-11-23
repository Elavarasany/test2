package nmk_backend.entity;

import jakarta.persistence.*;

@Entity
@Table(
        name = "pos_registers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pos_register_location_code",
                columnNames = {"location_id", "register_code"}
        )
)
public class PosRegister {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(name = "register_code", nullable = false, length = 40)
    private String registerCode;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;

    public Long getId() { return id; }
    public InventoryLocation getLocation() { return location; }
    public void setLocation(InventoryLocation location) { this.location = location; }
    public String getRegisterCode() { return registerCode; }
    public void setRegisterCode(String registerCode) { this.registerCode = registerCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
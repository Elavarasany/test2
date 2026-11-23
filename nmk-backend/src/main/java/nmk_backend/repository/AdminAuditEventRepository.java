package nmk_backend.repository;

import nmk_backend.entity.AdminAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditEventRepository extends JpaRepository<AdminAuditEvent, Long> {
}
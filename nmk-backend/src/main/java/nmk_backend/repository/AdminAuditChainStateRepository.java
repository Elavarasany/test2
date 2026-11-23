package nmk_backend.repository;

import nmk_backend.entity.AdminAuditChainState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminAuditChainStateRepository extends JpaRepository<AdminAuditChainState, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM AdminAuditChainState state WHERE state.id = :id")
    Optional<AdminAuditChainState> findByIdForUpdate(@Param("id") Integer id);
}

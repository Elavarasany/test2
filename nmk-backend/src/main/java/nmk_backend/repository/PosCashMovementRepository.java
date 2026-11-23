package nmk_backend.repository;

import nmk_backend.entity.PosCashMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PosCashMovementRepository extends JpaRepository<PosCashMovement, Long> {
    Optional<PosCashMovement> findByIdempotencyKey(String idempotencyKey);
    List<PosCashMovement> findByShiftIdOrderByCreatedAtAscIdAsc(Long shiftId);
}
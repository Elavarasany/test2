package nmk_backend.repository;

import nmk_backend.entity.PosShift;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosShiftRepository extends JpaRepository<PosShift, Long> {
    Optional<PosShift> findByIdempotencyKey(String idempotencyKey);
    Optional<PosShift> findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(Long registerId, String status);
    @EntityGraph(attributePaths = {"register", "register.location"})
    Page<PosShift> findByRegisterLocationId(Long locationId, Pageable pageable);
    Optional<PosShift> findFirstByOpenedByUsernameAndStatusOrderByOpenedAtDesc(String username, String status);
    @EntityGraph(attributePaths = {"register", "register.location"})
    List<PosShift> findByRegisterLocationIdAndOpenedAtGreaterThanEqualAndOpenedAtLessThanOrderByOpenedAtAscIdAsc(
            Long locationId, java.time.LocalDateTime from, java.time.LocalDateTime to);
    List<PosShift> findByRegisterLocationIdAndStatusOrderByOpenedAtDesc(Long locationId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PosShift s where s.id = :id")
    Optional<PosShift> findByIdForUpdate(@Param("id") Long id);
}
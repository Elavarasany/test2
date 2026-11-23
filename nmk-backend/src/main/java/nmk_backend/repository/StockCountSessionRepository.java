package nmk_backend.repository;

import nmk_backend.entity.StockCountSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockCountSessionRepository extends JpaRepository<StockCountSession, Long> {
    Optional<StockCountSession> findFirstByLocationIdAndStatusInOrderByStartedAtDesc(
            Long locationId, List<String> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockCountSession s where s.id = :id")
    Optional<StockCountSession> findByIdForUpdate(@Param("id") Long id);
}
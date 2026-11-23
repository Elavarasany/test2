package nmk_backend.repository;

import nmk_backend.entity.StockCountLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockCountLineRepository extends JpaRepository<StockCountLine, Long> {
    List<StockCountLine> findBySessionIdOrderByIdAsc(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockCountLine l where l.id = :lineId and l.session.id = :sessionId")
    Optional<StockCountLine> findBySessionAndIdForUpdate(
            @Param("sessionId") Long sessionId,
            @Param("lineId") Long lineId
    );
}
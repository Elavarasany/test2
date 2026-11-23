package nmk_backend.repository;

import nmk_backend.entity.OfferBar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

import java.util.List;

public interface OfferBarRepository extends JpaRepository<OfferBar, Long> {

    @Query("""
        SELECT offer
        FROM OfferBar offer
        WHERE offer.active = true
          AND (offer.startDateTime IS NULL OR offer.startDateTime <= :now)
          AND (offer.endDateTime IS NULL OR offer.endDateTime >= :now)
        ORDER BY offer.startDateTime DESC, offer.id DESC
    """)
    List<OfferBar> findCurrentlyValidOrdered(@Param("now") LocalDateTime now);
}
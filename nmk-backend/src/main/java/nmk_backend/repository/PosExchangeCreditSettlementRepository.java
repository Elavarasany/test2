package nmk_backend.repository;
import nmk_backend.entity.PosExchangeCreditSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public interface PosExchangeCreditSettlementRepository extends JpaRepository<PosExchangeCreditSettlement, Long> {
    boolean existsByExchangeId(Long exchangeId);
 @Query("""
  select coalesce(sum(c.amount), 0) from PosExchangeCreditSettlement c
  where c.exchange.originalSale.locationId = :locationId
    and c.exchange.createdAt >= :from and c.exchange.createdAt < :to
 """)
 BigDecimal sumExchangeCreditApplied(@Param("locationId") Long locationId,
  @Param("from") LocalDateTime from,@Param("to") LocalDateTime to);
}
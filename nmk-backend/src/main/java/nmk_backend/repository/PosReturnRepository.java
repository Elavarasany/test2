package nmk_backend.repository;

import nmk_backend.entity.PosReturn;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PosReturnRepository extends JpaRepository<PosReturn, Long> {

    Optional<PosReturn> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PosReturn r where r.idempotencyKey = :key")
    Optional<PosReturn> findByIdempotencyKeyForUpdate(@Param("key") String key);

    List<PosReturn> findBySaleIdOrderByCreatedAtDesc(Long saleId);

    @Query("select coalesce(sum(r.refundTotal), 0) from PosReturn r where r.sale.id = :saleId and r.status = 'COMPLETED'")
    BigDecimal sumRefundTotalBySaleId(@Param("saleId") Long saleId);

    @Query("""
        select r from PosReturn r
        where r.sale.locationId = :locationId
          and r.status = 'COMPLETED'
          and r.createdAt >= :from
          and r.createdAt < :to
        order by r.createdAt asc, r.id asc
    """)
    Page<PosReturn> findCompletedReturnsForReport(
            @Param("locationId") Long locationId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            Pageable pageable
    );
}

package nmk_backend.repository;

import nmk_backend.entity.PosSale;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosSaleRepository extends JpaRepository<PosSale, Long> {

    Optional<PosSale> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PosSale s WHERE s.idempotencyKey = :idempotencyKey")
    Optional<PosSale> findByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);
    Optional<PosSale> findByInvoiceNumber(String invoiceNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PosSale s WHERE s.id = :saleId")
    Optional<PosSale> findByIdForUpdate(@Param("saleId") Long saleId);

    List<PosSale> findAllByOrderByCreatedAtDesc();
    List<PosSale> findByLocationIdOrderByCreatedAtDesc(Long locationId);
    List<PosSale> findByCashierUsernameOrderByCreatedAtDesc(String cashierUsername);
    Page<PosSale> findByLocationId(Long locationId, Pageable pageable);

    Page<PosSale> findByLocationIdIn(java.util.Collection<Long> locationIds, Pageable pageable);

    @Query("""
        SELECT s FROM PosSale s
        WHERE s.locationId = :locationId
          AND s.status = 'COMPLETED'
          AND s.createdAt >= :from
          AND s.createdAt < :to
        ORDER BY s.createdAt ASC, s.id ASC
    """)
    Page<PosSale> findCompletedSalesForReport(
            @Param("locationId") Long locationId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            Pageable pageable
    );
}

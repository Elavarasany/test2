package nmk_backend.repository;

import nmk_backend.entity.PosExchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PosExchangeRepository extends JpaRepository<PosExchange, Long> {
    Optional<PosExchange> findByIdempotencyKey(String idempotencyKey);

    java.util.List<PosExchange> findByOriginalSaleIdOrderByCreatedAtDesc(Long originalSaleId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from PosExchange e where e.idempotencyKey = :key")
    Optional<PosExchange> findByIdempotencyKeyForUpdate(
        @org.springframework.data.repository.query.Param("key") String key
    );
}


package nmk_backend.repository;

import nmk_backend.entity.InventoryBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InventoryBalanceRepository
        extends JpaRepository<InventoryBalance, Long> {

    Optional<InventoryBalance> findByLocationIdAndVariantId(
            Long locationId,
            Long variantId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b
        FROM InventoryBalance b
        WHERE b.location.id = :locationId
          AND b.variant.id = :variantId
    """)
    Optional<InventoryBalance> findByLocationAndVariantForUpdate(
            @Param("locationId") Long locationId,
            @Param("variantId") Long variantId
    );

    List<InventoryBalance> findByLocationId(Long locationId);

    @EntityGraph(attributePaths = {"variant", "variant.product"})
    Page<InventoryBalance> findByLocationId(Long locationId, Pageable pageable);

    List<InventoryBalance> findByLocationIdAndVariantIdIn(Long locationId, List<Long> variantIds);

    List<InventoryBalance> findByVariantId(Long variantId);

    List<InventoryBalance> findByLocationIdAndOnHandQuantityLessThanEqual(
            Long locationId,
            Integer quantity
    );
}

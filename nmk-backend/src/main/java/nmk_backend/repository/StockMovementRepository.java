package nmk_backend.repository;

import nmk_backend.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockMovementRepository
        extends JpaRepository<StockMovement, Long> {

    Optional<StockMovement> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<StockMovement> findByLocationIdAndVariantIdOrderByCreatedAtDesc(
            Long locationId,
            Long variantId
    );

    @Query(
            value = """
                    select movement
                    from StockMovement movement
                    join fetch movement.location
                    join fetch movement.variant variant
                    join fetch variant.product
                    where movement.location.id = :locationId
                      and (:variantId is null or movement.variant.id = :variantId)
                    order by movement.createdAt desc, movement.id desc
                    """,
            countQuery = """
                    select count(movement)
                    from StockMovement movement
                    where movement.location.id = :locationId
                      and (:variantId is null or movement.variant.id = :variantId)
                    """
    )
    Page<StockMovement> findHistory(
            @Param("locationId") Long locationId,
            @Param("variantId") Long variantId,
            Pageable pageable
    );

    List<StockMovement> findByReferenceTypeAndReferenceId(
            String referenceType,
            Long referenceId
    );
}

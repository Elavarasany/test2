package nmk_backend.repository;

import nmk_backend.entity.RazorpaySettlementEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RazorpaySettlementEntryRepository extends JpaRepository<RazorpaySettlementEntry, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT entry FROM RazorpaySettlementEntry entry WHERE entry.settlement.id = :settlementId AND entry.providerEntityId = :entityId AND entry.entryType = :entryType")
    Optional<RazorpaySettlementEntry> findBySettlementAndProviderEntityForUpdate(
            @Param("settlementId") Long settlementId,
            @Param("entityId") String providerEntityId,
            @Param("entryType") String entryType);

    @Query("SELECT entry FROM RazorpaySettlementEntry entry WHERE entry.settlement.id = :settlementId ORDER BY entry.providerCreatedAt DESC, entry.id DESC")
    Page<RazorpaySettlementEntry> findBySettlementId(
            @Param("settlementId") Long settlementId, Pageable pageable);

    @EntityGraph(attributePaths = "settlement")
    Page<RazorpaySettlementEntry> findByMatchStatusIn(
            java.util.Collection<String> matchStatuses, Pageable pageable);
}

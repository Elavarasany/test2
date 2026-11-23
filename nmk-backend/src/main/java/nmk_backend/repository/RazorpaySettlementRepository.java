package nmk_backend.repository;

import nmk_backend.entity.RazorpaySettlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RazorpaySettlementRepository extends JpaRepository<RazorpaySettlement, Long> {
    Optional<RazorpaySettlement> findByProviderSettlementId(String providerSettlementId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT settlement FROM RazorpaySettlement settlement WHERE settlement.providerSettlementId = :providerId")
    Optional<RazorpaySettlement> findByProviderSettlementIdForUpdate(
            @Param("providerId") String providerSettlementId);
}

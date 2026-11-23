package nmk_backend.repository;

import nmk_backend.entity.RazorpayDispute;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RazorpayDisputeRepository extends JpaRepository<RazorpayDispute, Long> {
    Optional<RazorpayDispute> findByProviderDisputeId(String providerDisputeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT dispute FROM RazorpayDispute dispute WHERE dispute.providerDisputeId = :providerId")
    Optional<RazorpayDispute> findByProviderDisputeIdForUpdate(
            @Param("providerId") String providerDisputeId);

    Page<RazorpayDispute> findAllByOrderByRespondByAscProviderCreatedAtDesc(Pageable pageable);
}

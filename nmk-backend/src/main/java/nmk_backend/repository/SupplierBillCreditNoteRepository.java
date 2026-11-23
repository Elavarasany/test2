package nmk_backend.repository;

import nmk_backend.entity.SupplierBillCreditNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierBillCreditNoteRepository extends JpaRepository<SupplierBillCreditNote, Long> {
    Optional<SupplierBillCreditNote> findByIdempotencyKey(String idempotencyKey);
    boolean existsBySupplierIdAndCreditNoteNumberIgnoreCase(Long supplierId, String creditNoteNumber);
    List<SupplierBillCreditNote> findByBillIdOrderByIssueDateDescIdDesc(Long billId);
}

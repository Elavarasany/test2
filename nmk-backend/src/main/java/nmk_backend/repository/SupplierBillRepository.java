package nmk_backend.repository;

import nmk_backend.entity.SupplierBill;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SupplierBillRepository extends JpaRepository<SupplierBill, Long> {
    Optional<SupplierBill> findByIdempotencyKey(String idempotencyKey);
    boolean existsBySupplierIdAndInvoiceNumberIgnoreCase(Long supplierId, String invoiceNumber);

    @EntityGraph(attributePaths = {"supplier", "purchaseOrder", "purchaseOrder.supplier"})
    Page<SupplierBill> findBySupplierIdOrderByInvoiceDateDescCreatedAtDesc(Long supplierId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SupplierBill b where b.id = :id")
    Optional<SupplierBill> findByIdForUpdate(@Param("id") Long id);
}

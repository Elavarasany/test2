package nmk_backend.repository;

import nmk_backend.entity.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {
    Optional<SupplierPayment> findByIdempotencyKey(String idempotencyKey);

    @Query("select coalesce(sum(p.amount), 0) from SupplierPayment p where p.bill.id = :billId and p.status = 'CONFIRMED'")
    BigDecimal sumConfirmedAmountByBillId(@Param("billId") Long billId);

    List<SupplierPayment> findByBillIdOrderByPaidAtDescIdDesc(Long billId);
}

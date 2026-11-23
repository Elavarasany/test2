package nmk_backend.repository;
import nmk_backend.entity.PosRefundPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface PosRefundPaymentRepository extends JpaRepository<PosRefundPayment,Long> {
 @Query("select p from PosRefundPayment p where p.returnRecord.id = :returnId")
 List<PosRefundPayment> findByReturnRecordId(@Param("returnId") Long returnId);
}
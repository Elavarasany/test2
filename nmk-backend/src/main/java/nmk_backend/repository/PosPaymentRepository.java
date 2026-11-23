
package nmk_backend.repository;

import nmk_backend.entity.PosPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosPaymentRepository extends JpaRepository<PosPayment, Long> {

    List<PosPayment> findBySaleId(Long saleId);
}

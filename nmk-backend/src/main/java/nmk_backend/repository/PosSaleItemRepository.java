
package nmk_backend.repository;

import nmk_backend.entity.PosSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosSaleItemRepository extends JpaRepository<PosSaleItem, Long> {

    List<PosSaleItem> findBySaleId(Long saleId);

    List<PosSaleItem> findByProductId(Long productId);

    List<PosSaleItem> findByVariantId(Long variantId);
}

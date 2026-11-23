package nmk_backend.repository;

import nmk_backend.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByNameIgnoreCase(String name);
    Page<Supplier> findAllByOrderByNameAsc(Pageable pageable);
    Optional<Supplier> findByNameIgnoreCase(String name);
}
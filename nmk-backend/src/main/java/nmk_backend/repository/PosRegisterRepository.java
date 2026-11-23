package nmk_backend.repository;

import nmk_backend.entity.PosRegister;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosRegisterRepository extends JpaRepository<PosRegister, Long> {
    List<PosRegister> findByLocationIdAndActiveTrueOrderByRegisterCode(Long locationId);
    boolean existsByLocationIdAndRegisterCodeIgnoreCase(Long locationId, String registerCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PosRegister r where r.id = :id")
    Optional<PosRegister> findByIdForUpdate(@Param("id") Long id);
}
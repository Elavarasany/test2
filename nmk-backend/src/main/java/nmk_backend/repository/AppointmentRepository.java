
package nmk_backend.repository;

import nmk_backend.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    boolean existsByAppointmentDateAndAppointmentTimeAndStatusNot(
            java.time.LocalDate date,
            java.time.LocalTime time,
            String status
    );
}

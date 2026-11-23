
package nmk_backend.repository;

import nmk_backend.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByTrackingToken(String trackingToken);

    @Query("""
        SELECT o
        FROM Order o
        WHERE o.paymentCreationStatus IN :paymentCreationStatuses
          AND o.paymentStatus = :paymentStatus
          AND o.stockReserved = true
          AND o.orderDate < :orderDate
          AND o.reservationExpiresAt IS NOT NULL
          AND o.reservationExpiresAt <= :now
    """)
    Page<Order> findExpiredStalePaymentCreations(
            @Param("paymentCreationStatuses") List<String> paymentCreationStatuses,
            @Param("paymentStatus") String paymentStatus,
            @Param("orderDate") LocalDateTime orderDate,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    Optional<Order> findByRazorpayOrderId(String razorpayOrderId);

    Optional<Order> findByRazorpayPaymentId(String razorpayPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT o
        FROM Order o
        WHERE o.razorpayPaymentId = :razorpayPaymentId
    """)
    Optional<Order> findByRazorpayPaymentIdForUpdate(
            @Param("razorpayPaymentId") String razorpayPaymentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT o
        FROM Order o
        WHERE o.razorpayOrderId = :razorpayOrderId
    """)
    Optional<Order> findByRazorpayOrderIdForUpdate(
            @Param("razorpayOrderId") String razorpayOrderId
    );
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT o
    FROM Order o
    WHERE o.id = :id
""")
    Optional<Order> findByIdForUpdate(
            @Param("id") Long id
    );

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.items
        ORDER BY o.orderDate DESC
    """)
    List<Order> findAllWithItems();

    @Query("SELECT o.id FROM Order o")
    Page<Long> findAllOrderIds(Pageable pageable);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.items
        WHERE o.id IN :ids
    """)
    List<Order> findWithItemsByIdIn(@Param("ids") List<Long> ids);

    @Query(
            value = """
                SELECT
                    COALESCE(NULLIF(LOWER(TRIM(o.email)), ''), o.phone) AS customerKey,
                    MAX(o.customerName) AS customerName,
                    MAX(o.email) AS email,
                    MAX(o.phone) AS phone,
                    COUNT(o.id) AS totalOrders,
                    COALESCE(SUM(CASE WHEN UPPER(o.paymentStatus) = 'PAID'
                                      THEN o.totalAmount ELSE 0.00 END), 0.00) AS totalSpent
                FROM Order o
                GROUP BY COALESCE(NULLIF(LOWER(TRIM(o.email)), ''), o.phone)
                ORDER BY MAX(o.orderDate) DESC,
                         COALESCE(NULLIF(LOWER(TRIM(o.email)), ''), o.phone) ASC
                """,
            countQuery = """
                SELECT COUNT(DISTINCT COALESCE(NULLIF(LOWER(TRIM(o.email)), ''), o.phone))
                FROM Order o
                """
    )
    Page<CustomerOrderSummaryProjection> findCustomerSummaries(Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE (:email IS NOT NULL AND LOWER(TRIM(o.email)) = :email)
                   OR (:email IS NULL AND o.phone = :phone)
                """,
            countQuery = """
                SELECT COUNT(o)
                FROM Order o
                WHERE (:email IS NOT NULL AND LOWER(TRIM(o.email)) = :email)
                   OR (:email IS NULL AND o.phone = :phone)
                """
    )
    Page<Long> findCustomerOrderIds(
            @Param("email") String email,
            @Param("phone") String phone,
            Pageable pageable
    );

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.items
        WHERE o.stockReserved = true
          AND o.reservationExpiresAt <= :now
          AND o.paymentStatus = 'PENDING'
    """)
    List<Order> findExpiredReservations(
            @Param("now") LocalDateTime now
    );
    @Query("""
    SELECT o.id
    FROM Order o
    WHERE o.stockReserved = true
      AND o.reservationExpiresAt <= :now
      AND o.paymentStatus = 'PENDING'
    ORDER BY o.reservationExpiresAt ASC
""")
    List<Long> findExpiredReservationIds(
            @Param("now") LocalDateTime now
    );
}

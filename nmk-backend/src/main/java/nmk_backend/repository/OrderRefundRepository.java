package nmk_backend.repository;

import nmk_backend.entity.OrderRefund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRefundRepository extends JpaRepository<OrderRefund, Long> {
    Optional<OrderRefund> findByRazorpayRefundId(String refundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT refund FROM OrderRefund refund WHERE refund.razorpayRefundId = :refundId")
    Optional<OrderRefund> findByRazorpayRefundIdForUpdate(@Param("refundId") String refundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT refund FROM OrderRefund refund WHERE refund.requestIdempotencyKey = :key")
    Optional<OrderRefund> findByRequestIdempotencyKeyForUpdate(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT refund FROM OrderRefund refund WHERE refund.razorpayPaymentId = :paymentId AND refund.receipt = :receipt")
    Optional<OrderRefund> findByRazorpayPaymentIdAndReceiptForUpdate(
            @Param("paymentId") String paymentId, @Param("receipt") String receipt);

    Optional<OrderRefund> findByRequestIdempotencyKey(String key);

    @Query("SELECT refund FROM OrderRefund refund WHERE refund.order.id = :orderId ORDER BY refund.createdAt DESC")
    java.util.List<OrderRefund> findByOrderIdOrderByCreatedAtDesc(@Param("orderId") Long orderId);

    @Query("""
            SELECT COALESCE(SUM(refund.amountPaise), 0)
            FROM OrderRefund refund
            WHERE refund.order.id = :orderId
              AND refund.status = 'PROCESSED'
            """)
    Long sumProcessedAmountPaiseByOrderId(@Param("orderId") Long orderId);

    @Query("""
            SELECT COALESCE(SUM(refund.amountPaise), 0)
            FROM OrderRefund refund
            WHERE refund.order.id = :orderId
              AND refund.status IN ('PENDING', 'PROCESSED')
            """)
    Long sumActiveAmountPaiseByOrderId(@Param("orderId") Long orderId);
}

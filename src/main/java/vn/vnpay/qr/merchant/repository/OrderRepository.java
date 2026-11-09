package vn.vnpay.qr.merchant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.merchant.domain.Order;

public interface OrderRepository extends JpaRepository<Order, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.orderId = :orderId")
    java.util.Optional<Order> findForUpdate(@org.springframework.data.repository.query.Param("orderId") String orderId);
}

package vn.vnpay.qr.merchant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.merchant.domain.Order;

public interface OrderRepository extends JpaRepository<Order, String> {
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.merchantId=:merchantId and ((o.status=vn.vnpay.qr.merchant.domain.OrderStatus.PAID and (o.paidAt is null or (o.paidAt>=:start and o.paidAt<:end))) or o.txnRef in :refs or o.orderId in :ids) order by o.txnRef")
    java.util.List<Order> findReconciliationOrders(@org.springframework.data.repository.query.Param("merchantId") String merchantId,
            @org.springframework.data.repository.query.Param("start") java.time.Instant start,
            @org.springframework.data.repository.query.Param("end") java.time.Instant end,
            @org.springframework.data.repository.query.Param("refs") java.util.List<String> refs,
            @org.springframework.data.repository.query.Param("ids") java.util.List<String> ids);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.orderId = :orderId")
    java.util.Optional<Order> findForUpdate(@org.springframework.data.repository.query.Param("orderId") String orderId);
}

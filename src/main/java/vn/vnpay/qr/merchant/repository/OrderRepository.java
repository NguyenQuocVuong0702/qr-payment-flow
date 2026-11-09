package vn.vnpay.qr.merchant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.merchant.domain.Order;

public interface OrderRepository extends JpaRepository<Order, String> {
}

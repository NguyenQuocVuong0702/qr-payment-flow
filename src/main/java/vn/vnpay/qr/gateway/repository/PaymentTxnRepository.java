package vn.vnpay.qr.gateway.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.gateway.domain.PaymentTxn;

public interface PaymentTxnRepository extends JpaRepository<PaymentTxn, String> {
    Optional<PaymentTxn> findByMerchantIdAndOrderId(String merchantId, String orderId);
}

package vn.vnpay.qr.gateway.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.gateway.domain.PaymentTxn;

public interface PaymentTxnRepository extends JpaRepository<PaymentTxn, String> {
    @org.springframework.data.jpa.repository.Query("select p.txnRef from PaymentTxn p where p.status=vn.vnpay.qr.gateway.domain.PaymentStatus.PENDING and p.expireAt<=:now order by p.txnRef")
    java.util.List<String> findExpiryCandidates(@org.springframework.data.repository.query.Param("now") java.time.Instant now);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PaymentTxn p where p.txnRef = :txnRef")
    Optional<PaymentTxn> findForUpdate(@org.springframework.data.repository.query.Param("txnRef") String txnRef);
    Optional<PaymentTxn> findByMerchantIdAndOrderId(String merchantId, String orderId);
}

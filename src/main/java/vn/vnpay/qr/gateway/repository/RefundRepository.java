package vn.vnpay.qr.gateway.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.vnpay.qr.gateway.domain.Refund;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByTxnRefAndRequestId(String txnRef, UUID requestId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.txnRef = :txnRef")
    long totalFor(String txnRef);
}

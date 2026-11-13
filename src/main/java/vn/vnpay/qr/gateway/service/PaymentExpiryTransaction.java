package vn.vnpay.qr.gateway.service;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.gateway.domain.PaymentStatus;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;

@Service
public class PaymentExpiryTransaction {
    private final PaymentTxnRepository payments;
    private final Clock clock;
    public PaymentExpiryTransaction(PaymentTxnRepository payments, Clock clock) {
        this.payments = payments; this.clock = clock;
    }
    @Transactional
    public boolean expire(String txnRef) {
        var found = payments.findForUpdate(txnRef); // Same lock as B5; fresh read, no prior managed entity.
        if (found.isEmpty()) return false;
        var txn = found.get();
        if (txn.getStatus() != PaymentStatus.PENDING || clock.instant().isBefore(txn.getExpireAt())) return false;
        txn.expire();
        payments.flush();
        return true;
    }
}

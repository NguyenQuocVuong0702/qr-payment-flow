package vn.vnpay.qr.gateway.service;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;

@Service
public class PaymentExpiryJob {
    private final PaymentTxnRepository payments;
    private final PaymentExpiryTransaction transaction;
    private final Clock clock;
    public PaymentExpiryJob(PaymentTxnRepository payments, PaymentExpiryTransaction transaction, Clock clock) {
        this.payments = payments; this.transaction = transaction; this.clock = clock;
    }
    @Transactional(propagation = Propagation.NEVER)
    public int runOnce() {
        int expired = 0;
        for (String ref : payments.findExpiryCandidates(clock.instant())) if (transaction.expire(ref)) expired++;
        return expired;
    }
}

package vn.vnpay.qr.gateway.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.gateway.api.CreatePaymentRequest;
import vn.vnpay.qr.gateway.api.CreatePaymentResponse;

@Service
public class GatewayPaymentService {
    private final PaymentCreationTransaction transactions;

    public GatewayPaymentService(PaymentCreationTransaction transactions) {
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CreatePaymentResponse create(CreatePaymentRequest request) {
        try {
            return transactions.create(request);
        } catch (DataIntegrityViolationException ex) {
            // The failed insert transaction has already rolled back. Read the committed winner
            // in a new transaction, never in the rollback-only transaction.
            var winner = transactions.findWinner(request);
            if (winner != null) {
                return winner;
            }
            throw ex;
        }
    }
}

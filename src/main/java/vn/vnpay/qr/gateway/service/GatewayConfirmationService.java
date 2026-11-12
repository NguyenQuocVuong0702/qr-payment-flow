package vn.vnpay.qr.gateway.service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.vnpay.qr.gateway.api.ConfirmPaymentRequest;
@Service
public class GatewayConfirmationService {
    private final ConfirmationTransaction transaction;
    public GatewayConfirmationService(ConfirmationTransaction transaction) { this.transaction = transaction; }
    @Transactional(propagation = Propagation.NEVER)
    public ConfirmationOutcome confirm(String txnRef, ConfirmPaymentRequest request) {
        try { return transaction.execute(txnRef, request); }
        catch (DataIntegrityViolationException ex) {
            var result = transaction.winner(txnRef, request);
            if (result != null) return result;
            throw ex;
        }
    }
}

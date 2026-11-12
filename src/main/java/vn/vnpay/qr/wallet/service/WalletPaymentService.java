package vn.vnpay.qr.wallet.service;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.wallet.api.WalletPaymentRequest;
import vn.vnpay.qr.wallet.client.WalletConfirmClient;
import vn.vnpay.qr.wallet.client.WalletPaymentResult;

@Service
public class WalletPaymentService {
    private final WalletPinService pins;
    private final WalletConfirmClient gateway;
    public WalletPaymentService(WalletPinService pins, WalletConfirmClient gateway) { this.pins = pins; this.gateway = gateway; }
    @Transactional(propagation = Propagation.NEVER)
    public WalletPaymentResult pay(WalletPaymentRequest request) {
        var verification = pins.verify(request.userId(), request.pin());
        if (verification.errorCode() != null) {
            boolean locked = "PIN_LOCKED".equals(verification.errorCode());
            throw new ApiException(locked ? HttpStatus.LOCKED : HttpStatus.UNAUTHORIZED,
                    verification.errorCode(), locked ? "PIN locked for 15 minutes" : "Invalid PIN");
        }
        return gateway.confirm(request, verification.accountNo());
    }
}

package vn.vnpay.qr.wallet.api;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.wallet.client.WalletPaymentResult;
import vn.vnpay.qr.wallet.service.WalletPaymentService;
@RestController
public class WalletPaymentController {
    private final WalletPaymentService payments;
    public WalletPaymentController(WalletPaymentService payments) { this.payments = payments; }
    @PostMapping("/api/wallet/payments")
    public WalletPaymentResult pay(@Valid @RequestBody WalletPaymentRequest request) { return payments.pay(request); }
}

package vn.vnpay.qr.wallet.service;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.wallet.api.ScanResponse;
import vn.vnpay.qr.wallet.client.WalletGatewayClient;
import vn.vnpay.qr.wallet.qr.PaymentQrParser;

@Service
public class WalletScanService {
    private final PaymentQrParser parser;
    private final WalletGatewayClient gateway;
    private final Clock clock;

    public WalletScanService(PaymentQrParser parser, WalletGatewayClient gateway, Clock clock) {
        this.parser = parser;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public ScanResponse scan(String qrString) {
        var qr = parser.parse(qrString);
        var payment = gateway.get(qr.txnRef());
        if (qr.amount() != payment.amount()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_MISMATCH", "QR amount differs from Gateway amount");
        }
        // Follow the error table/design: terminal success/failure stays NOT_PAYABLE; expiry has its own code.
        if ("EXPIRED".equals(payment.status()) || "PENDING".equals(payment.status())
                && !clock.instant().isBefore(payment.expireAt().toInstant())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TXN_EXPIRED", "Payment transaction has expired");
        }
        if (!"PENDING".equals(payment.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TXN_NOT_PAYABLE", "Payment transaction is not pending");
        }
        return new ScanResponse(payment.txnRef(), payment.merchantName(), payment.merchantCity(), payment.amount(),
                payment.currency(), payment.description(), payment.expireAt());
    }
}

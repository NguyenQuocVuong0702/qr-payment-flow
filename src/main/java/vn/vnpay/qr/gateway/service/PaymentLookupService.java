package vn.vnpay.qr.gateway.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.api.MerchantPaymentDetails;
import vn.vnpay.qr.gateway.api.WalletPaymentDetails;
import vn.vnpay.qr.gateway.repository.MerchantRepository;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class PaymentLookupService {
    private final PaymentTxnRepository payments;
    private final MerchantRepository merchants;

    public PaymentLookupService(PaymentTxnRepository payments, MerchantRepository merchants) {
        this.payments = payments;
        this.merchants = merchants;
    }

    @Transactional(readOnly = true)
    public Object lookup(String txnRef, LookupCaller caller) {
        var txn = payments.findById(txnRef).filter(value -> caller.wallet()
                || value.getMerchantId().equals(caller.merchantId())).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "TXN_NOT_FOUND", "Payment transaction not found"));
        if (!caller.wallet()) {
            return new MerchantPaymentDetails(txn.getTxnRef(), txn.getOrderId(), txn.getAmount(), txn.getStatus(),
                    txn.getBankRef(), date(txn.getCreatedAt()), date(txn.getPaidAt()));
        }
        var merchant = merchants.findById(txn.getMerchantId()).orElseThrow();
        return new WalletPaymentDetails(txn.getTxnRef(), merchant.getName(), merchant.getCity(), txn.getAmount(),
                "VND", txn.getDescription(), txn.getStatus(), date(txn.getExpireAt()));
    }

    private OffsetDateTime date(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

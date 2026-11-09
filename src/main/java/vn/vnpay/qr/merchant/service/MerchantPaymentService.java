package vn.vnpay.qr.merchant.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.merchant.client.GatewayPayment;
import vn.vnpay.qr.merchant.client.GatewayPaymentClient;

@Service
public class MerchantPaymentService {
    private final MerchantPaymentStore store;
    private final GatewayPaymentClient gateway;

    public MerchantPaymentService(MerchantPaymentStore store, GatewayPaymentClient gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    @Transactional(propagation = Propagation.NEVER)
    public GatewayPayment start(String orderId, int expireMinutes) {
        var snapshot = store.load(orderId); // Read transaction ends before HTTP.
        var payment = gateway.create(snapshot, expireMinutes);
        store.attach(orderId, payment.txnRef()); // A separate short write transaction.
        return payment;
    }
}

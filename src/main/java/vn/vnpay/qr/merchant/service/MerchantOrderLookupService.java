package vn.vnpay.qr.merchant.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.vnpay.qr.merchant.api.OrderDetails;
import vn.vnpay.qr.merchant.client.GatewayPaymentClient;
import vn.vnpay.qr.merchant.domain.OrderStatus;

@Service
public class MerchantOrderLookupService {
    private final MerchantOrderLookupStore store;
    private final GatewayPaymentClient gateway;
    public MerchantOrderLookupService(MerchantOrderLookupStore store, GatewayPaymentClient gateway) {
        this.store = store; this.gateway = gateway;
    }
    @Transactional(propagation = Propagation.NEVER)
    public OrderDetails sync(String orderId) {
        var snapshot = store.load(orderId);
        if (snapshot.status() != OrderStatus.WAITING_PAYMENT) return snapshot;
        var payment = gateway.lookup(snapshot); // No DB transaction or row lock across HTTP.
        return store.apply(snapshot, payment);
    }
}

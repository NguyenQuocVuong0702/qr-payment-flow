package vn.vnpay.qr.merchant.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.merchant.api.OrderDetails;
import vn.vnpay.qr.merchant.client.GatewayPaymentStatus;
import vn.vnpay.qr.merchant.config.MerchantProperties;
import vn.vnpay.qr.merchant.domain.OrderStatus;
import vn.vnpay.qr.merchant.repository.OrderRepository;

@Service
public class MerchantOrderLookupStore {
    private final OrderRepository orders;
    private final MerchantProperties merchant;
    public MerchantOrderLookupStore(OrderRepository orders, MerchantProperties merchant) {
        this.orders = orders; this.merchant = merchant;
    }
    @Transactional(readOnly = true)
    public OrderDetails load(String orderId) {
        return OrderDetails.from(orders.findById(orderId).filter(order -> merchant.id().equals(order.getMerchantId()))
                .orElseThrow(MerchantOrderLookupStore::notFound));
    }
    @Transactional
    public OrderDetails apply(OrderDetails snapshot, GatewayPaymentStatus payment) {
        var order = orders.findForUpdate(snapshot.orderId()).filter(value -> merchant.id().equals(value.getMerchantId()))
                .orElseThrow(MerchantOrderLookupStore::notFound);
        // Recheck after HTTP: IPN or another lookup may have committed while the request was in flight.
        if (!snapshot.txnRef().equals(order.getTxnRef()) || payment.amount() != order.getAmount()
                || !payment.orderId().equals(order.getOrderId()) || !payment.txnRef().equals(order.getTxnRef())) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_CONFLICT", "Order payment binding changed during lookup");
        }
        if (order.getStatus() == OrderStatus.WAITING_PAYMENT) {
            switch (payment.status()) {
                case "SUCCESS" -> order.confirmPayment(true, payment.paidAt().toInstant());
                case "FAILED" -> order.confirmPayment(false, null);
                case "EXPIRED" -> order.expire();
                default -> { /* PENDING/PROCESSING never infer a terminal state from local time. */ }
            }
        }
        orders.flush();
        return OrderDetails.from(order);
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }
}

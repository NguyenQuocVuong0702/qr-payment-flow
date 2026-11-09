package vn.vnpay.qr.merchant.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.merchant.domain.Order;
import vn.vnpay.qr.merchant.domain.OrderStatus;
import vn.vnpay.qr.merchant.repository.OrderRepository;

@Service
public class MerchantPaymentStore {
    private final OrderRepository orders;

    public MerchantPaymentStore(OrderRepository orders) { this.orders = orders; }

    @Transactional(readOnly = true)
    public OrderPaymentSnapshot load(String orderId) {
        var order = orders.findById(orderId).orElseThrow(MerchantPaymentStore::notFound);
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.WAITING_PAYMENT) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_CONFLICT", "Order cannot start payment");
        }
        return new OrderPaymentSnapshot(order.getOrderId(), order.getMerchantId(), order.getAmount(), order.getDescription());
    }

    @Transactional
    public void attach(String orderId, String txnRef) {
        Order order = orders.findForUpdate(orderId).orElseThrow(MerchantPaymentStore::notFound);
        order.attachPayment(txnRef);
        orders.flush();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }
}

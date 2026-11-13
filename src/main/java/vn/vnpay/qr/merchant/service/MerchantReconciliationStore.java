package vn.vnpay.qr.merchant.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.time.BusinessDay;
import vn.vnpay.qr.merchant.api.OrderDetails;
import vn.vnpay.qr.merchant.config.MerchantProperties;
import vn.vnpay.qr.merchant.repository.OrderRepository;

@Service
public class MerchantReconciliationStore {
    private final OrderRepository orders;
    private final MerchantProperties merchant;
    public MerchantReconciliationStore(OrderRepository orders, MerchantProperties merchant) { this.orders = orders; this.merchant = merchant; }
    @Transactional(readOnly = true)
    public List<OrderDetails> snapshot(BusinessDay day, List<String> refs, List<String> ids) {
        return orders.findReconciliationOrders(merchant.id(), day.start(), day.end(), refs, ids).stream().map(OrderDetails::from).toList();
    }
}

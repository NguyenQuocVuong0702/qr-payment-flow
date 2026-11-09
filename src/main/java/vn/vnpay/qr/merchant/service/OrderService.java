package vn.vnpay.qr.merchant.service;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.merchant.api.CreateOrderRequest;
import vn.vnpay.qr.merchant.api.CreateOrderResponse;
import vn.vnpay.qr.merchant.config.MerchantProperties;
import vn.vnpay.qr.merchant.domain.Order;
import vn.vnpay.qr.merchant.repository.OrderIdGenerator;
import vn.vnpay.qr.merchant.repository.OrderRepository;

import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final OrderIdGenerator ids;
    private final MerchantProperties merchant;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderIdGenerator ids, MerchantProperties merchant, Clock clock) {
        this.orders = orders;
        this.ids = ids;
        this.merchant = merchant;
        this.clock = clock;
    }

    @Transactional
    public CreateOrderResponse create(CreateOrderRequest request) {
        var now = clock.instant();
        var order = orders.save(new Order(ids.next(now), merchant.id(), request.amount(), request.description(), now));
        // The transactional proxy commits before the controller can send 201.
        return new CreateOrderResponse(order.getOrderId(), order.getMerchantId(), order.getAmount(),
                order.getDescription(), order.getStatus(), OffsetDateTime.ofInstant(order.getCreatedAt(), BUSINESS_ZONE));
    }
}

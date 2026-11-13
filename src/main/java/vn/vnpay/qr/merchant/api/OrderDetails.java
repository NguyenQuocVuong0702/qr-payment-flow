package vn.vnpay.qr.merchant.api;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;
import vn.vnpay.qr.merchant.domain.Order;
import vn.vnpay.qr.merchant.domain.OrderStatus;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderDetails(String orderId, String merchantId, long amount, String description,
        OrderStatus status, String txnRef, OffsetDateTime createdAt, OffsetDateTime paidAt) {
    public static OrderDetails from(Order order) {
        return new OrderDetails(order.getOrderId(), order.getMerchantId(), order.getAmount(), order.getDescription(),
                order.getStatus(), order.getTxnRef(), OffsetDateTime.ofInstant(order.getCreatedAt(), BUSINESS_ZONE),
                order.getPaidAt() == null ? null : OffsetDateTime.ofInstant(order.getPaidAt(), BUSINESS_ZONE));
    }
}

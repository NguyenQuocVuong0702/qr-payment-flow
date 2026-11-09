package vn.vnpay.qr.merchant.api;

import java.time.OffsetDateTime;

import vn.vnpay.qr.merchant.domain.OrderStatus;

public record CreateOrderResponse(String orderId, String merchantId, long amount,
        String description, OrderStatus status, OffsetDateTime createdAt) {
}

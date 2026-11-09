package vn.vnpay.qr.gateway.api;

import java.time.OffsetDateTime;
import vn.vnpay.qr.gateway.domain.PaymentStatus;

public record CreatePaymentResponse(String txnRef, String orderId, long amount,
        PaymentStatus status, OffsetDateTime expireAt, String qrString) {
}

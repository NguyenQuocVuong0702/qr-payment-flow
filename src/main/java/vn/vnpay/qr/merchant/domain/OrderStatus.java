package vn.vnpay.qr.merchant.domain;

public enum OrderStatus {
    CREATED,
    WAITING_PAYMENT,
    PAID,
    PAYMENT_FAILED,
    EXPIRED
}

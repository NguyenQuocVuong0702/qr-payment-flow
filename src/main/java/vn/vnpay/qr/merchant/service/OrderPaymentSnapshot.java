package vn.vnpay.qr.merchant.service;

public record OrderPaymentSnapshot(String orderId, String merchantId, long amount, String description) {
}

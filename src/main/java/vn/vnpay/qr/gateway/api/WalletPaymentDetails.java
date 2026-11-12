package vn.vnpay.qr.gateway.api;

import java.time.OffsetDateTime;
import vn.vnpay.qr.gateway.domain.PaymentStatus;

public record WalletPaymentDetails(String txnRef, String merchantName, String merchantCity, long amount,
        String currency, String description, PaymentStatus status, OffsetDateTime expireAt) {
}

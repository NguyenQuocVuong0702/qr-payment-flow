package vn.vnpay.qr.wallet.api;

import java.time.OffsetDateTime;

public record ScanResponse(String txnRef, String merchantName, String merchantCity, long amount,
        String currency, String description, OffsetDateTime expireAt) {
}

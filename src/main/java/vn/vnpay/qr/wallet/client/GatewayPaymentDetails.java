package vn.vnpay.qr.wallet.client;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Wallet-owned HTTP DTO; never imports Gateway's domain or DTO types. */
public record GatewayPaymentDetails(String txnRef, String merchantName, String merchantCity, Long amount,
        String currency, String description, String status,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime expireAt) {
}

package vn.vnpay.qr.merchant.client;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Merchant's HTTP contract; does not import Gateway's DTOs or entities. */
public record GatewayPayment(String txnRef, String orderId, long amount,
        String status,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime expireAt,
        String qrString) {
}

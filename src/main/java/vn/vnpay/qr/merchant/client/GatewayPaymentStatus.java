package vn.vnpay.qr.merchant.client;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Merchant-owned B7 HTTP contract; no Gateway domain dependency. */
public record GatewayPaymentStatus(String txnRef, String orderId, Long amount, String status, String bankRef,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime createdAt,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime paidAt) { }

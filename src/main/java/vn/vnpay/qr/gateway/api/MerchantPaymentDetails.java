package vn.vnpay.qr.gateway.api;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;
import vn.vnpay.qr.gateway.domain.PaymentStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantPaymentDetails(String txnRef, String orderId, long amount, PaymentStatus status,
        String bankRef, OffsetDateTime createdAt, OffsetDateTime paidAt) {
}

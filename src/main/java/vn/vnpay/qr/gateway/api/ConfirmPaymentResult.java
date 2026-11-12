package vn.vnpay.qr.gateway.api;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfirmPaymentResult(String txnRef, String status, long amount, String bankRef,
        OffsetDateTime paidAt, String message, String errorCode) { }

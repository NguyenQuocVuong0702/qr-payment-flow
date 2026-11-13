package vn.vnpay.qr.gateway.api;

import java.time.OffsetDateTime;
import java.util.List;

public record ReconciliationResponse(String merchantId, String date, long successCount, long successAmount,
        List<Transaction> transactions) {
    public record Transaction(String txnRef, String orderId, long amount, String status, String bankRef, OffsetDateTime paidAt) { }
}

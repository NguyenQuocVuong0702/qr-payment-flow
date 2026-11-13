package vn.vnpay.qr.merchant.api;

import java.util.List;

public record ReconciliationResult(String date, long matched, List<Difference> missingInMerchant,
        List<Difference> missingInGateway, List<AmountMismatch> amountMismatch, long gatewayTotal, long merchantTotal) {
    public record Difference(String txnRef, String orderId, long amount) { }
    public record AmountMismatch(String txnRef, String orderId, long gatewayAmount, long merchantAmount) { }
}

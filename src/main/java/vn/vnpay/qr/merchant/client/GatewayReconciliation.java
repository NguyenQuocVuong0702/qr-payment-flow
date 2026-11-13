package vn.vnpay.qr.merchant.client;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.*;
import vn.vnpay.qr.common.time.BusinessDay;

/** Merchant's validated HTTP contract; no Gateway domain/DTO imports. */
public record GatewayReconciliation(String merchantId, String date, Long successCount, Long successAmount, List<Transaction> transactions) {
    public record Transaction(String txnRef, String orderId, Long amount, String status, String bankRef,
            @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime paidAt) { }

    public static GatewayReconciliation read(JsonNode json, ObjectMapper mapper, String merchantId, BusinessDay day) throws IOException {
        requireFields(json, Set.of("merchantId", "date", "successCount", "successAmount", "transactions"));
        require(json.path("transactions").isArray());
        for (var item : json.path("transactions")) requireFields(item, Set.of("txnRef", "orderId", "amount", "status", "bankRef", "paidAt"));
        var result = mapper.treeToValue(json, GatewayReconciliation.class);
        require(merchantId.equals(result.merchantId()) && day.date().toString().equals(result.date()));
        require(result.successCount() != null && result.successCount() == result.transactions().size() && result.successAmount() != null);
        var refs = new HashSet<String>(); var orders = new HashSet<String>(); var banks = new HashSet<String>();
        long total = 0;
        for (var txn : result.transactions()) {
            require(txn.txnRef() != null && txn.txnRef().matches("TX[0-9]{8}") && refs.add(txn.txnRef()));
            require(txn.orderId() != null && txn.orderId().matches("[A-Za-z0-9_-]{1,32}") && orders.add(txn.orderId()));
            require(txn.amount() != null && txn.amount() >= 1000 && txn.amount() <= 500_000_000 && "SUCCESS".equals(txn.status()));
            require(txn.bankRef() != null && !txn.bankRef().isBlank() && txn.bankRef().length() <= 32 && banks.add(txn.bankRef()));
            require(txn.paidAt() != null && day.contains(txn.paidAt().toInstant()));
            total = Math.addExact(total, txn.amount());
        }
        require(total == result.successAmount());
        return result;
    }
    private static void requireFields(JsonNode json, Set<String> fields) {
        require(json != null && json.isObject() && json.size() == fields.size());
        for (String field : fields) require(json.hasNonNull(field));
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid reconciliation response"); }
}

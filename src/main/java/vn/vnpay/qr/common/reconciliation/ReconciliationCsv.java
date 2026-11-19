package vn.vnpay.qr.common.reconciliation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import vn.vnpay.qr.common.crypto.HmacSigner;

/** Fixed CSV exchange format for a single signed B8 snapshot. */
@Component
public class ReconciliationCsv {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final List<String> FIELDS = List.of("type", "merchantId", "date", "successCount", "successAmount",
            "txnRef", "orderId", "amount", "status", "bankRef", "paidAt", "signature");
    private static final String HEADER = String.join(",", FIELDS);
    // Every value belongs to a machine field with a restricted alphabet. There are no quoted or free-text cells.
    private static final Pattern CELL = Pattern.compile("[A-Za-z0-9_.:+-]*");
    private final ObjectMapper mapper;
    private final HmacSigner hmac;

    public ReconciliationCsv(ObjectMapper mapper, HmacSigner hmac) {
        this.mapper = mapper;
        this.hmac = hmac;
    }

    public byte[] write(JsonNode report, String secret) {
        var csv = new StringBuilder(HEADER).append("\r\n");
        row(csv, "SUMMARY", report.path("merchantId").asText(), report.path("date").asText(),
                report.path("successCount").asText(), report.path("successAmount").asText(),
                "", "", "", "", "", "", hmac.sign(report, secret));
        for (var txn : report.path("transactions")) {
            row(csv, "PAYMENT", "", "", "", "", txn.path("txnRef").asText(), txn.path("orderId").asText(),
                    txn.path("amount").asText(), txn.path("status").asText(), txn.path("bankRef").asText(),
                    txn.path("paidAt").asText(), "");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public ObjectNode read(byte[] bytes, String secret) {
        try (var lines = new String(bytes, StandardCharsets.UTF_8).lines()) {
            var rows = lines.iterator();
            require(rows.hasNext() && HEADER.equals(rows.next()) && rows.hasNext());
            String[] summary = columns(rows.next());
            require("SUMMARY".equals(summary[0]) && empty(summary, 5, 11));
            var report = mapper.createObjectNode().put("merchantId", summary[1]).put("date", summary[2])
                    .put("successCount", unsignedLong(summary[3])).put("successAmount", unsignedLong(summary[4]));
            var transactions = report.putArray("transactions");
            while (rows.hasNext()) {
                String[] payment = columns(rows.next());
                require("PAYMENT".equals(payment[0]) && empty(payment, 1, 5) && payment[11].isEmpty());
                transactions.addObject().put("txnRef", payment[5]).put("orderId", payment[6])
                        .put("amount", unsignedLong(payment[7])).put("status", payment[8])
                        .put("bankRef", payment[9]).put("paidAt", payment[10]);
            }
            require(hmac.verify(report, secret, summary[11]));
            return report;
        }
    }

    private static String[] columns(String line) {
        String[] values = line.split(",", -1);
        require(values.length == FIELDS.size());
        for (String value : values) require(CELL.matcher(value).matches());
        return values;
    }

    private static void row(StringBuilder csv, String... values) {
        for (String value : values) require(CELL.matcher(value).matches());
        csv.append(String.join(",", values)).append("\r\n");
    }

    private static boolean empty(String[] values, int start, int end) {
        for (int i = start; i < end; i++) if (!values[i].isEmpty()) return false;
        return true;
    }

    private static long unsignedLong(String value) {
        require(value.matches("0|[1-9][0-9]{0,18}"));
        return Long.parseLong(value);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid reconciliation CSV");
    }
}

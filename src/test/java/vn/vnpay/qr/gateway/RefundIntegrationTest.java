package vn.vnpay.qr.gateway;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.vnpay.qr.common.crypto.HmacSigner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:refund;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void resetDatabase() {
        jdbc.update("DELETE FROM refund_ledger_entry");
        jdbc.update("DELETE FROM refund_txn");
        jdbc.update("DELETE FROM ledger_entry");
        jdbc.update("DELETE FROM idempotency");
        jdbc.update("DELETE FROM payment_txn");
        jdbc.update("DELETE FROM merchant WHERE merchant_id='MC002'");
        jdbc.update("UPDATE merchant SET account_no='9000001' WHERE merchant_id='MC001'");
        jdbc.update("UPDATE account SET balance=CASE account_no WHEN '1000001' THEN 1000000 WHEN '1000002' THEN 10000 ELSE 0 END");
    }

    @Test void partialThenFullRefundRetainsGrossReconciliationAndReplayIsStable() throws Exception {
        var payment = pay(createPayment());
        var txn = payment.path("txnRef").asText();
        var requestId = UUID.randomUUID();
        var partial = refund(txn, 20_000, requestId);
        assertThat(partial.getResponse().getStatus()).isEqualTo(200);
        var first = mapper.readTree(partial.getResponse().getContentAsString());
        assertThat(first.path("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(first.path("remainingAmount").asLong()).isEqualTo(35_000);
        assertThat(hmac.verify(first, "secret-mc001", partial.getResponse().getHeader("X-Signature"))).isTrue();
        assertThat(balance("1000001")).isEqualTo(965_000);
        assertThat(balance("9000001")).isEqualTo(35_000);
        assertThat(paymentStatus(txn)).isEqualTo("SUCCESS");

        var last = refund(txn, 35_000, UUID.randomUUID());
        assertThat(last.getResponse().getStatus()).isEqualTo(200);
        var full = mapper.readTree(last.getResponse().getContentAsString());
        assertThat(full.path("status").asText()).isEqualTo("REFUNDED");
        assertThat(full.path("remainingAmount").asLong()).isZero();
        assertThat(full.path("totalRefunded").asLong()).isEqualTo(55_000);
        assertThat(full.path("refundRef").asText()).isNotEqualTo(first.path("refundRef").asText());
        assertThat(refund(txn, 20_000, requestId).getResponse().getContentAsString())
                .isEqualTo(partial.getResponse().getContentAsString());
        assertThat(refund(txn, 21_000, requestId).getResponse().getStatus()).isEqualTo(409);
        assertThat(refund(txn, 1, UUID.randomUUID()).getResponse().getStatus()).isEqualTo(422);
        assertThat(paymentStatus(txn)).isEqualTo("REFUNDED");
        assertThat(balance("1000001")).isEqualTo(1_000_000);
        assertThat(balance("9000001")).isZero();
        assertThat(jdbc.queryForObject("SELECT bank_ref FROM payment_txn WHERE txn_ref=?", String.class, txn))
                .isEqualTo(payment.path("bankRef").asText());
        assertThat(count("ledger_entry")).isEqualTo(2);
        assertThat(count("idempotency")).isOne();
        assertThat(count("ipn_outbox")).isOne();
        assertRefundLedger(2);

        LocalDate day = OffsetDateTime.parse(payment.path("paidAt").asText()).toLocalDate();
        var fields = mapper.createObjectNode().put("merchantId", "MC001").put("date", day.toString());
        mvc.perform(get("/api/gateway/reconciliation").param("merchantId", "MC001").param("date", day.toString())
                .header("X-Merchant-Id", "MC001").header("X-Signature", hmac.sign(fields, "secret-mc001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.successAmount").value(55_000))
                .andExpect(jsonPath("$.transactions[0].status").value("SUCCESS"));
    }

    @Test void fullRefundUsesOriginalCreditedAccountAfterMerchantAccountChanges() throws Exception {
        var txn = createPayment();
        pay(txn);
        jdbc.update("UPDATE merchant SET account_no='1000002' WHERE merchant_id='MC001'");
        var result = refund(txn, 55_000, UUID.randomUUID());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(paymentStatus(txn)).isEqualTo("REFUNDED");
        assertThat(balance("1000001")).isEqualTo(1_000_000);
        assertThat(balance("9000001")).isZero();
        assertThat(balance("1000002")).isEqualTo(10_000);
        assertRefundLedger(1);
    }

    @Test void invalidStateAmountAndMerchantLeaveFundsUntouched() throws Exception {
        var txn = createPayment();
        assertThat(refund(txn, 1, UUID.randomUUID()).getResponse().getStatus()).isEqualTo(422);
        pay(txn);
        assertThat(refund(txn, 55_001, UUID.randomUUID()).getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(post("/api/gateway/payments/{txnRef}/refund", txn)
                .contentType("application/json").content(refundBody(1, UUID.randomUUID()).toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        jdbc.update("INSERT INTO merchant(merchant_id,name,city,mcc,secret_key,ipn_url,account_no) VALUES ('MC002','OTHER','HANOI','5499','other-secret','http://localhost/ipn','9000001')");
        assertThat(refund(txn, refundBody(1, UUID.randomUUID()), "MC002", "other-secret").getResponse().getStatus()).isEqualTo(404);
        assertThat(balance("9000001")).isEqualTo(55_000);
        assertThat(balance("1000001")).isEqualTo(945_000);
        assertRefundLedger(0);
    }

    @Test void declinedReverseTransferLeavesNoRefundAndCanBeRetriedAfterFundsReturn() throws Exception {
        var txn = createPayment();
        pay(txn);
        jdbc.update("UPDATE account SET balance=10 WHERE account_no='9000001'");
        var requestId = UUID.randomUUID();
        var result = refund(txn, 55_000, requestId);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(paymentStatus(txn)).isEqualTo("SUCCESS");
        assertThat(balance("1000001")).isEqualTo(945_000);
        assertThat(balance("9000001")).isEqualTo(10);
        assertRefundLedger(0);
        jdbc.update("UPDATE account SET balance=55000 WHERE account_no='9000001'");
        assertThat(refund(txn, 55_000, requestId).getResponse().getStatus()).isEqualTo(200);
        assertRefundLedger(1);
    }

    @Test void concurrentRefundsCannotExceedPaymentAmount() throws Exception {
        var txn = createPayment();
        pay(txn);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); return refund(txn, 40_000, UUID.randomUUID()); });
            var second = pool.submit(() -> { gate.await(); return refund(txn, 40_000, UUID.randomUUID()); });
            gate.countDown();
            assertThat(new int[] {first.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(10, TimeUnit.SECONDS).getResponse().getStatus()}).containsExactlyInAnyOrder(200, 422);
        }
        assertThat(balance("9000001")).isEqualTo(15_000);
        assertThat(balance("1000001")).isEqualTo(985_000);
        assertRefundLedger(1);
    }

    @Test void concurrentReplayProducesOneRefundAndIdenticalResponses() throws Exception {
        var txn = createPayment();
        pay(txn);
        var id = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); return refund(txn, 55_000, id).getResponse(); });
            var second = pool.submit(() -> { gate.await(); return refund(txn, 55_000, id).getResponse(); });
            gate.countDown();
            var firstResponse = first.get(10, TimeUnit.SECONDS);
            var secondResponse = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResponse.getStatus()).isEqualTo(200);
            assertThat(secondResponse.getStatus()).isEqualTo(200);
            assertThat(firstResponse.getContentAsString()).isEqualTo(secondResponse.getContentAsString());
        }
        assertThat(balance("1000001")).isEqualTo(1_000_000);
        assertThat(balance("9000001")).isZero();
        assertRefundLedger(1);
    }

    private String createPayment() throws Exception {
        var body = mapper.createObjectNode().put("merchantId", "MC001").put("orderId", "ORDER-REFUND")
                .put("amount", 55_000).put("description", "Ao thun").put("expireMinutes", 15);
        body.put("signature", hmac.sign(body, "secret-mc001"));
        var response = mvc.perform(post("/api/gateway/payments").contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("txnRef").asText();
    }

    private ObjectNode pay(String txn) throws Exception {
        var body = mapper.createObjectNode().put("payerAccount", "1000001").put("amount", 55_000)
                .put("requestId", UUID.randomUUID().toString());
        var response = mvc.perform(post("/api/gateway/payments/{txnRef}/confirm", txn).contentType("application/json")
                .content(body.toString()).header("X-Wallet-Signature",
                        hmac.sign(body.deepCopy().put("txnRef", txn), "wallet-payment-secret-dev-only")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return (ObjectNode) mapper.readTree(response.getContentAsString());
    }

    private MvcResult refund(String txn, long amount, UUID id) throws Exception {
        return refund(txn, refundBody(amount, id), "MC001", "secret-mc001");
    }

    private ObjectNode refundBody(long amount, UUID id) {
        return mapper.createObjectNode().put("amount", amount).put("requestId", id.toString());
    }

    private MvcResult refund(String txn, ObjectNode body, String merchant, String key) throws Exception {
        var fields = body.deepCopy().put("merchantId", merchant).put("action", "refund").put("txnRef", txn);
        return mvc.perform(post("/api/gateway/payments/{txnRef}/refund", txn).contentType("application/json")
                .content(body.toString()).header("X-Merchant-Id", merchant).header("X-Signature", hmac.sign(fields, key))).andReturn();
    }

    private String paymentStatus(String txn) {
        return jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?", String.class, txn);
    }

    private long balance(String account) {
        return jdbc.queryForObject("SELECT balance FROM account WHERE account_no=?", Long.class, account);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertRefundLedger(long refunds) {
        assertThat(count("refund_txn")).isEqualTo(refunds);
        assertThat(count("refund_ledger_entry")).isEqualTo(refunds * 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT refund_id FROM refund_ledger_entry GROUP BY refund_id HAVING COUNT(*)<>2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)<>0)", Long.class)).isZero();
    }
}

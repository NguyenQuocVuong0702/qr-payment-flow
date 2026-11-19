package vn.vnpay.qr.merchant;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.reconciliation.ReconciliationCsv;
import vn.vnpay.qr.gateway.ipn.IpnWorker;
import vn.vnpay.qr.gateway.service.PaymentExpiryJob;
import vn.vnpay.qr.merchant.client.GatewayPaymentClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Real HTTP from test -> Merchant/Wallet -> Gateway, persistent state in H2, controlled callback/network faults. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:reconcile-b8;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test") @Import(ReconciliationHttpIntegrationTest.TestConfig.class)
class ReconciliationHttpIntegrationTest {
    static final Instant NOW=Instant.parse("2026-11-13T03:00:00Z");
    static final String DATE="2026-11-13";
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final ExecutorService THREADS=Executors.newCachedThreadPool();
    static final AtomicInteger reconciliationCalls=new AtomicInteger(),ipnCalls=new AtomicInteger();
    static final HttpServer PROXY=proxy();
    static volatile int appPort;
    static volatile String mode="forward",ipnMode="forward";
    static volatile ObjectMapper json;
    static volatile HmacSigner signer;
    static volatile CountDownLatch entered,release;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired MutableClock clock;
    @Autowired IpnWorker worker;
    @Autowired PaymentExpiryJob expiry;
    @MockitoSpyBean GatewayPaymentClient gateway;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.merchant.gateway.base-url",()->"http://127.0.0.1:"+PROXY.getAddress().getPort());
        registry.add("app.wallet.gateway.base-url",()->"http://127.0.0.1:"+PROXY.getAddress().getPort());
    }
    @BeforeEach void clean() {
        appPort=port;json=mapper;signer=hmac;mode="forward";ipnMode="forward";reconciliationCalls.set(0);ipnCalls.set(0);
        entered=new CountDownLatch(1);release=new CountDownLatch(1);clock.now.set(NOW);
        jdbc.update("DELETE FROM ledger_entry");jdbc.update("DELETE FROM idempotency");jdbc.update("DELETE FROM payment_txn");jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM merchant WHERE merchant_id='MC002'");jdbc.update("DELETE FROM account WHERE account_no NOT IN ('1000001','1000002','9000001')");
        jdbc.update("UPDATE account SET balance=CASE account_no WHEN '1000001' THEN 1000000 WHEN '1000002' THEN 10000 ELSE 0 END");
        jdbc.update("UPDATE wallet_user SET failed_pin_attempts=0,pin_locked_until=NULL");
        jdbc.update("UPDATE merchant SET ipn_url=? WHERE merchant_id='MC001'","http://127.0.0.1:"+PROXY.getAddress().getPort()+"/ipn");
    }
    @AfterAll static void stop(){release.countDown();PROXY.stop(0);THREADS.shutdownNow();}

    @Test void scenario1FullB1ToB8FlowMatchesOneAndNeverMovesMoneyDuringReconciliation() throws Exception {
        var payment=start(55000);var result=pay(payment,"U001");assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(worker.runOnce()).isTrue();assertThat(orderState(payment)).isEqualTo("PAID");
        assertThat(post("/api/merchant/orders/"+payment.orderId()+"/sync",null,200).path("status").asText()).isEqualTo("PAID");
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return call.callRealMethod();})
                .when(gateway).reconciliation(anyString(),any());
        String before=snapshot();var report=reconcile(DATE,200);assertReport(report,1,0,0,0,55000,55000);
        assertThat(report.size()).isEqualTo(7);assertThat(snapshot()).isEqualTo(before);assertThat(reconcile(DATE,200)).isEqualTo(report);
        assertThat(snapshot()).isEqualTo(before);assertThat(balance("1000001")).isEqualTo(945000);assertThat(balance("9000001")).isEqualTo(55000);invariants(1010000);
    }
    @Test void twoSuccessesWithBothIpnsHaveExactSignedMinimalGatewayProjection() throws Exception {
        var first=start(55000);pay(first,"U001");worker.runOnce();var second=start(55000);pay(second,"U001");worker.runOnce();
        assertReport(reconcile(DATE,200),2,0,0,0,110000,110000);assertThat(ipnCalls).hasValue(2);
        var response=gatewayGet("MC001",DATE,"MC001",signature("MC001",DATE));assertThat(response.statusCode()).isEqualTo(200);
        var body=mapper.readTree(response.body());assertThat(body.size()).isEqualTo(5);
        assertThat(hmac.verify(body,"secret-mc001",response.headers().firstValue("X-Signature").orElse(null))).isTrue();
        assertThat(response.headers().firstValue("Cache-Control").orElse(null)).isEqualTo("no-store");
        var refs=new ArrayList<String>();for(var txn:body.path("transactions")){assertThat(txn.size()).isEqualTo(6);refs.add(txn.path("txnRef").asText());
            for(String field:List.of("payerAccount","qrString","secret_key","description","account_no"))assertThat(txn.has(field)).isFalse();}
        assertThat(refs).isSorted();assertThat(refs).containsExactly(first.txnRef(),second.txnRef());invariants(1010000);
    }
    @Test void scenario11ThreeFailedIpnsAreReportedWithoutRepairAndSeparateB7SyncCanRepair() throws Exception {
        var payment=start(55000);pay(payment,"U001");ipnMode="500";
        worker.runOnce();clock.now.set(NOW.plusSeconds(10));worker.runOnce();clock.now.set(NOW.plusSeconds(40));worker.runOnce();
        assertThat(ipnCalls).hasValue(3);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ipn_log",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT state FROM ipn_outbox",String.class)).isEqualTo("EXHAUSTED");
        String before=snapshot();var report=reconcile(DATE,200);assertReport(report,0,1,0,0,55000,0);
        assertThat(report.path("missingInMerchant").get(0).path("txnRef").asText()).isEqualTo(payment.txnRef());
        assertThat(snapshot()).isEqualTo(before);assertThat(orderState(payment)).isEqualTo("WAITING_PAYMENT");
        post("/api/merchant/orders/"+payment.orderId()+"/sync",null,200);
        assertReport(reconcile(DATE,200),1,0,0,0,55000,55000);invariants(1010000);
    }
    @Test void missingInGatewayIsReportedFromPaidLocalSetWithoutAnyWrite() throws Exception {
        var payment=start(55000);markLocalPaid(payment);String before=snapshot();
        var report=reconcile(DATE,200);assertReport(report,0,0,1,0,0,55000);
        assertThat(report.path("missingInGateway").get(0).path("txnRef").asText()).isEqualTo(payment.txnRef());assertThat(snapshot()).isEqualTo(before);
    }
    @Test void amountMismatchIsNotAlsoMatchedOrMissingAndTotalsUseEachSidesActualAmount() throws Exception {
        var payment=start(55000);pay(payment,"U001");worker.runOnce();jdbc.update("UPDATE orders SET amount=56000 WHERE order_id=?",payment.orderId());
        String before=snapshot();var report=reconcile(DATE,200);assertReport(report,0,0,0,1,55000,56000);
        var mismatch=report.path("amountMismatch").get(0);assertThat(mismatch.size()).isEqualTo(4);
        assertThat(mismatch.path("gatewayAmount").asLong()).isEqualTo(55000);assertThat(mismatch.path("merchantAmount").asLong()).isEqualTo(56000);
        assertThat(snapshot()).isEqualTo(before);invariants(1010000);
    }
    @Test void allDifferenceCategoriesAreDisjointAndDoNotLoseOrDuplicateTransactions() throws Exception {
        var matched=start(55000);pay(matched,"U001");worker.runOnce();
        var mismatch=start(55000);pay(mismatch,"U001");worker.runOnce();jdbc.update("UPDATE orders SET amount=56000 WHERE order_id=?",mismatch.orderId());
        var missingGateway=start(55000);markLocalPaid(missingGateway);
        var missingMerchant=start(55000);pay(missingMerchant,"U001");
        String before=snapshot();var report=reconcile(DATE,200);assertReport(report,1,1,1,1,165000,166000);
        assertThat(report.path("missingInMerchant").get(0).path("txnRef").asText()).isEqualTo(missingMerchant.txnRef());
        assertThat(report.path("missingInGateway").get(0).path("txnRef").asText()).isEqualTo(missingGateway.txnRef());
        assertThat(report.path("amountMismatch").get(0).path("txnRef").asText()).isEqualTo(mismatch.txnRef());assertThat(snapshot()).isEqualTo(before);
    }

    @Test void csvReconciliationMatchesJsonReportAndWorksWithoutCallingGateway() throws Exception {
        var matched = start(55000);
        pay(matched, "U001");
        worker.runOnce();
        var mismatch = start(55000);
        pay(mismatch, "U001");
        worker.runOnce();
        jdbc.update("UPDATE orders SET amount=56000 WHERE order_id=?", mismatch.orderId());
        markLocalPaid(start(55000));
        var missingMerchant = start(55000);
        pay(missingMerchant, "U001");

        String before = snapshot();
        var expected = reconcile(DATE, 200);
        assertReport(expected, 1, 1, 1, 1, 165000, 166000);
        var exported = downloadCsv("MC001", DATE, "MC001", signature("MC001", DATE));
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/csv");
        assertThat(exported.headers().firstValue("Content-Disposition").orElseThrow()).contains("reconciliation-" + DATE + ".csv");
        assertThat(exported.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");

        mode = "500";
        int calls = reconciliationCalls.get();
        assertThat(importCsv(DATE, exported.body(), 200)).isEqualTo(expected);
        assertThat(importCsv(DATE, exported.body(), 200)).isEqualTo(expected);
        assertThat(reconciliationCalls.get()).isEqualTo(calls);
        assertThat(snapshot()).isEqualTo(before);
        invariants(1010000);
    }

    @Test void csvEmptySnapshotStillCarriesVerifiedMerchantAndDate() throws Exception {
        byte[] file = downloadCsv("MC001", DATE, "MC001", signature("MC001", DATE)).body();
        assertReport(importCsv(DATE, file, 200), 0, 0, 0, 0, 0, 0);
        assertThat(importCsv("2026-11-14", file, 400).path("errorCode").asText()).isEqualTo("INVALID_CSV");
    }

    @Test void csvDownloadRejectsWrongSignatureAndCrossMerchantRequests() throws Exception {
        assertThat(downloadCsv("MC001", DATE, "MC001", "wrong").statusCode()).isEqualTo(401);
        assertThat(downloadCsv("MC002", DATE, "MC001", signature("MC002", DATE)).statusCode()).isEqualTo(401);
    }

    @Test void csvImportRejectsAnotherMerchantEvenWhenTheSigningKeyMatches() throws Exception {
        jdbc.update("INSERT INTO merchant(merchant_id,name,city,mcc,secret_key,ipn_url,account_no) VALUES ('MC002','OTHER','HANOI','5499','secret-mc001','http://localhost/ipn','9000001')");
        var response = downloadCsv("MC002", DATE, "MC002", signature("MC002", DATE));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(importCsv(DATE, response.body(), 400).path("errorCode").asText()).isEqualTo("INVALID_CSV");
    }

    @ParameterizedTest
    @ValueSource(strings = {"edited-amount", "missing-payment", "duplicated-payment", "invalid-header"})
    void csvImportRejectsTamperedOrMalformedFilesWithoutWritingData(String change) throws Exception {
        var payment = start(55000);
        pay(payment, "U001");
        worker.runOnce();
        byte[] file = downloadCsv("MC001", DATE, "MC001", signature("MC001", DATE)).body();
        var lines = new String(file, StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(3);
        String changed = switch (change) {
            // Change both values so totals still match. The signature must reject this, even when the snapshot is otherwise valid.
            case "edited-amount" -> String.join("\n", lines.get(0), lines.get(1).replace(",55000,", ",56000,"),
                    lines.get(2).replace(",55000,", ",56000,"));
            case "missing-payment" -> String.join("\n", lines.get(0), lines.get(1));
            case "duplicated-payment" -> String.join("\n", lines.get(0), lines.get(1), lines.get(2), lines.get(2));
            default -> String.join("\n", "invalid header", lines.get(1), lines.get(2));
        };
        String before = snapshot();
        assertThat(importCsv(DATE, changed.getBytes(StandardCharsets.UTF_8), 400).path("errorCode").asText()).isEqualTo("INVALID_CSV");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void csvImportRejectsOversizedBodies() throws Exception {
        byte[] oversized = new byte[ReconciliationCsv.MAX_BYTES + 1];
        assertThat(importCsv(DATE, oversized, 413).path("errorCode").asText()).isEqualTo("CSV_TOO_LARGE");
    }
    @Test void emptyDayReturnsAllSevenFieldsAndZeroTotals() throws Exception {assertReport(reconcile(DATE,200),0,0,0,0,0,0);}
    @Test void manyTransactionsInTheSameDayAreAllCountedOnce() throws Exception {
        for(int i=1;i<=6;i++){var payment=start(i*1000);pay(payment,"U001");worker.runOnce();}
        assertReport(reconcile(DATE,200),6,0,0,0,21000,21000);invariants(1010000);
    }
    @Test void totalsLargerThanIntegerMaxValueRemainExactLongs() throws Exception {
        jdbc.update("UPDATE account SET balance=4000000000 WHERE account_no='1000001'");
        for(int i=0;i<6;i++){var payment=start(500000000);pay(payment,"U001");worker.runOnce();}
        assertReport(reconcile(DATE,200),6,0,0,0,3000000000L,3000000000L);invariants(4000010000L);
    }
    @Test void differentDaysAreSeparatedByPaidAt() throws Exception {
        var first=start(25000);pay(first,"U001");worker.runOnce();clock.now.set(NOW.plusSeconds(86400));
        var second=start(30000);pay(second,"U001");worker.runOnce();
        assertReport(reconcile(DATE,200),1,0,0,0,25000,25000);assertReport(reconcile("2026-11-14",200),1,0,0,0,30000,30000);
    }
    @ParameterizedTest @CsvSource({"2026-11-12T16:59:59.999999Z,0","2026-11-12T17:00:00Z,1",
            "2026-11-13T16:59:59.999999Z,1","2026-11-13T17:00:00Z,0","2026-11-13T00:00:00Z,1"})
    void vietnamMidnightBoundsAreInclusiveAtStartAndExclusiveAtEnd(String instant,int included) throws Exception {
        clock.now.set(Instant.parse(instant));var payment=start(55000);pay(payment,"U001");worker.runOnce();
        assertReport(reconcile(DATE,200),included,0,0,0,55000L*included,55000L*included);
    }
    @Test void createdYesterdayButPaidTodayBelongsToTodaysReport() throws Exception {
        clock.now.set(Instant.parse("2026-11-12T16:59:00Z"));var payment=start(55000);clock.now.set(Instant.parse("2026-11-12T17:01:00Z"));
        pay(payment,"U001");worker.runOnce();assertReport(reconcile(DATE,200),1,0,0,0,55000,55000);assertReport(reconcile("2026-11-12",200),0,0,0,0,0,0);
    }
    @Test void failedPendingAndExpiredTransactionsNeverEnterSuccessReport() throws Exception {
        var failed=start(55000);assertThat(pay(failed,"U002").path("status").asText()).isEqualTo("FAILED");worker.runOnce();
        assertThat(orderState(failed)).isEqualTo("PAYMENT_FAILED");var expired=start(1000);clock.now.set(NOW.plusSeconds(900));expiry.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,expired.txnRef())).isEqualTo("EXPIRED");
        start(1000);String before=snapshot();assertReport(reconcile(DATE,200),0,0,0,0,0,0);assertThat(snapshot()).isEqualTo(before);invariants(1010000);
    }
    @Test void eachMerchantCanReadOnlyItsOwnSuccessesAndWalletCredentialIsRejected() throws Exception {
        var own=start(55000);pay(own,"U001");worker.runOnce();
        jdbc.update("INSERT INTO account(account_no,owner_name,balance,version) VALUES ('9000002','Other',0,0)");
        jdbc.update("INSERT INTO merchant(merchant_id,name,city,mcc,secret_key,ipn_url,account_no) VALUES ('MC002','OTHER','HANOI','5499','other-key','http://localhost/ipn','9000002')");
        var body=mapper.createObjectNode().put("merchantId","MC002").put("orderId","OTHER1").put("amount",1000).put("description","Ao thun").put("expireMinutes",15);
        body.put("signature",hmac.sign(body,"other-key"));var other=post("/api/gateway/payments",body,201);
        pay(new Payment("OTHER1",other.path("txnRef").asText(),other.path("qrString").asText(),1000),"U001");
        assertReport(reconcile(DATE,200),1,0,0,0,55000,55000);
        String otherSignature=hmac.sign(mapper.createObjectNode().put("merchantId","MC002").put("date",DATE),"other-key");
        var response=gatewayGet("MC002",DATE,"MC002",otherSignature);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("successAmount").asLong()).isEqualTo(1000);
        assertThat(gatewayGet("MC002",DATE,"MC001",signature("MC002",DATE)).statusCode()).isEqualTo(401);
        var wallet=HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/gateway/reconciliation?merchantId=MC001&date="+DATE))
                .header("Authorization","Bearer wallet-read-token-dev-only").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(wallet.statusCode()).isEqualTo(401);invariants(1010000);
    }
    @ParameterizedTest @ValueSource(strings={"wrong-signature","missing-signature","duplicate-signature","wrong-merchant","wrong-date",
            "duplicate-ref","duplicate-order","duplicate-bank","wrong-count","wrong-total","string-count","float-amount","string-amount","zero-amount",
            "overflow-amount","missing-transactions","null-transactions","missing-paidAt","missing-bankRef","missing-status","wrong-status","bad-paidAt","outside-day",
            "bad-ref","bad-order","wrong-order-binding","wrong-ref-binding","extra-field","extra-txn-field","nested-amount","bad-json","duplicate-json-key","trailing-json","root-array"})
    void rejectsMalformedOrUntrustedResponsesWithoutAnyDatabaseWrites(String failure) throws Exception {
        var payment=start(55000);pay(payment,"U001");worker.runOnce();mode=failure;String before=snapshot();
        assertThat(reconcile(DATE,502).path("errorCode").asText()).isEqualTo("INVALID_GATEWAY_RESPONSE");assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"500","drop","timeout","body-timeout","http-404","redirect","oversized"})
    void httpFailuresNeverBecomeAnEmptyOrInferredReport(String failure) throws Exception {
        mode=failure;String before=snapshot();var result=reconcile(DATE,502);
        assertThat(result.path("errorCode").asText()).isEqualTo(Set.of("500","drop","timeout","body-timeout").contains(failure)?"GATEWAY_UNAVAILABLE":"INVALID_GATEWAY_RESPONSE");
        assertThat(snapshot()).isEqualTo(before);if(failure.equals("redirect"))assertThat(reconciliationCalls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings={"2026-02-30","2026-2-03"," 2026-11-13","2026-11-13 ","0000-01-01","2026-13-01","not-a-date",""})
    void invalidDateIsRejectedBeforeCallingGateway(String date) throws Exception {
        assertThat(reconcile(date,400).path("errorCode").asText()).isEqualTo("INVALID_DATE");assertThat(reconciliationCalls).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"date\":null}"})
    void missingDateUsesInvalidDate(String body) throws Exception {assertThat(postRaw("/api/merchant/reconcile",body,400).path("errorCode").asText()).isEqualTo("INVALID_DATE");}
    @ParameterizedTest @ValueSource(strings={"{\"date\":42}","{\"date\":true}","{\"date\":[]}","{\"date\":\"2026-11-13\",\"merchantId\":\"MC002\"}",
            "[]","null","{","{\"date\":\"2026-11-13\",\"date\":\"2026-11-13\"}","{\"date\":\"2026-11-13\"} {}"})
    void invalidRequestJsonUsesCommonEnvelope(String body) throws Exception {assertThat(postRaw("/api/merchant/reconcile",body,400).path("errorCode").asText()).isEqualTo("INVALID_REQUEST");}
    @Test void gatewayRejectsMissingDuplicateMixedOrTamperedCredentialsAndQueries() throws Exception {
        String path=base()+"/api/gateway/reconciliation?merchantId=MC001&date="+DATE;
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(gatewayGet("MC001",DATE,"MC001","wrong").statusCode()).isEqualTo(401);
        assertThat(gatewayGet("MC001","2026-11-12","MC001",signature("MC001",DATE)).statusCode()).isEqualTo(401);
        var duplicate=HttpRequest.newBuilder(URI.create(path)).header("X-Merchant-Id","MC001").header("X-Signature",signature("MC001",DATE)).header("X-Signature",signature("MC001",DATE)).GET().build();
        assertThat(HTTP.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var mixed=HttpRequest.newBuilder(URI.create(path)).header("X-Merchant-Id","MC001").header("X-Signature",signature("MC001",DATE)).header("Authorization","Bearer wallet-read-token-dev-only").GET().build();
        assertThat(HTTP.send(mixed,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        for(String suffix:List.of("&date="+DATE,"&extra=1"))assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(path+suffix)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        var invalid=gatewayGet("MC001","2026-02-30","MC001",signature("MC001","2026-02-30"));assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(hmac.verify(mapper.readTree(invalid.body()),"secret-mc001",invalid.headers().firstValue("X-Signature").orElse(null))).isTrue();
    }
    @Test void alternatePaidAtOffsetForTheSameInstantIsAcceptedAfterRawJsonSignatureVerification() throws Exception {
        var payment=start(55000);pay(payment,"U001");worker.runOnce();mode="alternate-offset";assertReport(reconcile(DATE,200),1,0,0,0,55000,55000);
    }
    @Test void malformedLocalPaidOrdersFailClosedInsteadOfDisappearingFromTheReport() throws Exception {
        var payment=start(55000);jdbc.update("UPDATE orders SET status='PAID',paid_at=NULL WHERE order_id=?",payment.orderId());String before=snapshot();
        assertThat(reconcile(DATE,409).path("errorCode").asText()).isEqualTo("INVALID_MERCHANT_DATA");assertThat(snapshot()).isEqualTo(before);
        jdbc.update("UPDATE orders SET txn_ref=NULL,paid_at=? WHERE order_id=?",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),payment.orderId());
        assertThat(reconcile(DATE,409).path("errorCode").asText()).isEqualTo("INVALID_MERCHANT_DATA");
    }
    @Test void ipnCanCommitWhileReconciliationHttpIsInFlightWithoutHoldingALocalTransaction() throws Exception {
        var payment=start(55000);pay(payment,"U001");mode="block";var pool=Executors.newSingleThreadExecutor();
        try {
            var report=pool.submit(()->reconcile(DATE,200));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(worker.runOnce()).isTrue();release.countDown();assertReport(report.get(5,TimeUnit.SECONDS),1,0,0,0,55000,55000);
        } finally {release.countDown();pool.shutdownNow();}
    }

    Payment start(long amount) throws Exception {
        var order=post("/api/merchant/orders",mapper.createObjectNode().put("amount",amount).put("description","Ao thun"),201);
        var payment=post("/api/merchant/orders/"+order.path("orderId").asText()+"/payments",mapper.createObjectNode().put("expireMinutes",15),201);
        post("/api/wallet/scan",mapper.createObjectNode().put("userId","U001").put("qrString",payment.path("qrString").asText()),200);
        return new Payment(order.path("orderId").asText(),payment.path("txnRef").asText(),payment.path("qrString").asText(),amount);
    }
    JsonNode pay(Payment payment,String user) throws Exception {return post("/api/wallet/payments",mapper.createObjectNode().put("userId",user).put("txnRef",payment.txnRef())
            .put("amount",payment.amount()).put("pin","123456").put("requestId",UUID.randomUUID().toString()),200);}
    JsonNode reconcile(String date,int expected) throws Exception {return post("/api/merchant/reconcile",mapper.createObjectNode().put("date",date),expected);}
    JsonNode post(String path,JsonNode body,int expected) throws Exception {return postRaw(path,body==null?"":body.toString(),expected);}
    JsonNode postRaw(String path,String body,int expected) throws Exception {
        var response=HTTP.send(HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s: %s",path,response.body()).isEqualTo(expected);return mapper.readTree(response.body());
    }
    HttpResponse<String> gatewayGet(String merchant,String date,String headerId,String signature) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/gateway/reconciliation?merchantId="+merchant+"&date="+date))
                .header("X-Merchant-Id",headerId).header("X-Signature",signature).GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> downloadCsv(String merchant, String date, String headerId, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/api/gateway/reconciliation/csv?merchantId=" + merchant + "&date=" + date))
                .header("X-Merchant-Id", headerId).header("X-Signature", signature).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode importCsv(String date, byte[] file, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/api/merchant/reconcile/csv?date=" + date))
                .header("Content-Type", "text/csv").POST(HttpRequest.BodyPublishers.ofByteArray(file)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("CSV import: %s", response.body()).isEqualTo(expected);
        return mapper.readTree(response.body());
    }
    String signature(String merchant,String date){return hmac.sign(mapper.createObjectNode().put("merchantId",merchant).put("date",date),"secret-mc001");}
    String orderState(Payment payment){return jdbc.queryForObject("SELECT status FROM orders WHERE order_id=?",String.class,payment.orderId());}
    void markLocalPaid(Payment payment){jdbc.update("UPDATE orders SET status='PAID',paid_at=? WHERE order_id=?",OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),payment.orderId());}
    void assertReport(JsonNode report,long matched,int missingMerchant,int missingGateway,int mismatch,long gatewayTotal,long merchantTotal) {
        assertThat(report.path("matched").asLong()).isEqualTo(matched);assertThat(report.path("missingInMerchant").size()).isEqualTo(missingMerchant);
        assertThat(report.path("missingInGateway").size()).isEqualTo(missingGateway);assertThat(report.path("amountMismatch").size()).isEqualTo(mismatch);
        assertThat(report.path("gatewayTotal").asLong()).isEqualTo(gatewayTotal);assertThat(report.path("merchantTotal").asLong()).isEqualTo(merchantTotal);
    }
    long balance(String account){return jdbc.queryForObject("SELECT balance FROM account WHERE account_no=?",Long.class,account);}
    void invariants(long total) {
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM account",Long.class)).isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT bank_ref FROM ledger_entry GROUP BY bank_ref HAVING COUNT(*)<>2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)<>0)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT txn_ref FROM ledger_entry GROUP BY txn_ref HAVING COUNT(DISTINCT bank_ref)>1)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency WHERE http_status IS NULL OR response_body IS NULL",Long.class)).isZero();
    }
    String snapshot(){return jdbc.queryForList("SELECT * FROM account ORDER BY account_no").toString()+jdbc.queryForList("SELECT * FROM ledger_entry ORDER BY id")
            +jdbc.queryForList("SELECT * FROM payment_txn ORDER BY txn_ref")+jdbc.queryForList("SELECT * FROM orders ORDER BY order_id")
            +jdbc.queryForList("SELECT request_id,txn_ref,payer_account,amount,http_status,CAST(response_body AS VARCHAR) AS body,created_at,version FROM idempotency ORDER BY request_id")
            +jdbc.queryForList("SELECT txn_ref,CAST(request_body AS VARCHAR) AS body,state,attempt_count,next_attempt_at,lease_until,lease_token,version FROM ipn_outbox ORDER BY txn_ref")
            +jdbc.queryForList("SELECT id,txn_ref,attempt,CAST(request_body AS VARCHAR) AS body,response_code,sent_at,finished_at FROM ipn_log ORDER BY id");}
    String base(){return "http://127.0.0.1:"+port;}
    record Payment(String orderId,String txnRef,String qr,long amount) { }

    static HttpServer proxy() {
        try {var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(THREADS);
            server.createContext("/ipn",exchange->{try {
                ipnCalls.incrementAndGet();byte[] body=exchange.getRequestBody().readAllBytes();
                if(ipnMode.equals("500")){exchange.sendResponseHeaders(500,-1);return;}
                var reply=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+"/api/merchant/ipn")).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(reply.statusCode(),reply.body().length);exchange.getResponseBody().write(reply.body());
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});
            server.createContext("/api/gateway",exchange->{try {
                boolean reconciliation=exchange.getRequestURI().getPath().equals("/api/gateway/reconciliation");String current=mode;
                if(reconciliation){reconciliationCalls.incrementAndGet();
                    if(current.equals("drop"))return;
                    if(current.equals("timeout")){Thread.sleep(6000);return;}
                    if(current.equals("body-timeout")){exchange.sendResponseHeaders(200,100);Thread.sleep(6000);return;}
                    if(current.equals("500")){exchange.sendResponseHeaders(500,-1);return;}
                    if(current.equals("redirect")){exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+exchange.getRequestURI());exchange.sendResponseHeaders(302,-1);return;}}
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+exchange.getRequestURI())).header("Content-Type","application/json");
                for(String name:List.of("X-Merchant-Id","X-Signature","X-Wallet-Signature","Authorization")){String value=exchange.getRequestHeaders().getFirst(name);if(value!=null)request.header(name,value);}
                if(exchange.getRequestMethod().equals("GET"))request.GET();else request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                var reply=HTTP.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());byte[] body=reply.body();String signature=reply.headers().firstValue("X-Signature").orElse("");int status=reply.statusCode();
                if(reconciliation){
                    var object=(ObjectNode)json.readTree(body);var array=(ArrayNode)object.path("transactions");ObjectNode txn=array.isEmpty()?null:(ObjectNode)array.get(0);
                    switch(current){
                        case "wrong-merchant" -> object.put("merchantId","MC002");
                        case "wrong-date" -> object.put("date","2026-11-12");
                        case "duplicate-ref","duplicate-order","duplicate-bank" -> {
                            var copy=txn.deepCopy();if(!current.equals("duplicate-ref"))copy.put("txnRef","TX99999999");
                            if(!current.equals("duplicate-order"))copy.put("orderId","OTHER_ORDER");if(!current.equals("duplicate-bank"))copy.put("bankRef","BK99999999999999");
                            array.add(copy);object.put("successCount",2).put("successAmount",txn.path("amount").asLong()*2);
                        }
                        case "wrong-count" -> object.put("successCount",999);
                        case "wrong-total" -> object.put("successAmount",999);
                        case "string-count" -> object.put("successCount","1");
                        case "float-amount" -> txn.put("amount",55000.0);
                        case "string-amount" -> txn.put("amount","55000");
                        case "zero-amount" -> txn.put("amount",0);
                        case "overflow-amount" -> txn.put("amount",new java.math.BigInteger("9223372036854775808"));
                        case "nested-amount" -> txn.set("amount",json.createObjectNode().put("value",55000));
                        case "missing-transactions" -> object.remove("transactions");
                        case "null-transactions" -> object.putNull("transactions");
                        case "missing-paidAt" -> txn.remove("paidAt");
                        case "missing-bankRef" -> txn.remove("bankRef");
                        case "missing-status" -> txn.remove("status");
                        case "wrong-status" -> txn.put("status","FAILED");
                        case "bad-paidAt" -> txn.put("paidAt","not-a-date");
                        case "outside-day" -> txn.put("paidAt","2026-11-14T00:00:00+07:00");
                        case "bad-ref" -> txn.put("txnRef","TX_INVALID");
                        case "bad-order" -> txn.put("orderId","A&B");
                        case "wrong-order-binding" -> txn.put("orderId","OTHER_ORDER");
                        case "wrong-ref-binding" -> txn.put("txnRef","TX99999999");
                        case "extra-field" -> object.put("secret","should-not-be-here");
                        case "extra-txn-field" -> txn.put("payerAccount","1000001");
                        case "alternate-offset" -> txn.put("paidAt",NOW.atOffset(ZoneOffset.ofHours(-7)).toString());
                        case "http-404" -> {object=json.createObjectNode().put("errorCode","TXN_NOT_FOUND");status=404;}
                        case "oversized" -> object.put("extra","A".repeat(4*1024*1024));
                        default -> { }
                    }
                    body=json.writeValueAsBytes(object);signature=signer.sign(object,"secret-mc001");
                    if(current.equals("wrong-signature"))signature="0".repeat(64);
                    if(current.equals("bad-json"))body="{".getBytes(StandardCharsets.UTF_8);
                    if(current.equals("duplicate-json-key"))body="{\"merchantId\":\"MC001\",\"merchantId\":\"MC002\"}".getBytes(StandardCharsets.UTF_8);
                    if(current.equals("trailing-json"))body=(new String(body,StandardCharsets.UTF_8)+" {}").getBytes(StandardCharsets.UTF_8);
                    if(current.equals("root-array"))body="[]".getBytes(StandardCharsets.UTF_8);
                    if(current.equals("block")){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");}
                    if(!current.equals("missing-signature"))exchange.getResponseHeaders().set("X-Signature",signature);
                    if(current.equals("duplicate-signature"))exchange.getResponseHeaders().add("X-Signature",signature);
                }else if(!signature.isEmpty())exchange.getResponseHeaders().set("X-Signature",signature);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();return server;
        }catch(IOException ex){throw new ExceptionInInitializerError(ex);}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestConfig {@Bean @Primary MutableClock b8Clock(){return new MutableClock();}}
}

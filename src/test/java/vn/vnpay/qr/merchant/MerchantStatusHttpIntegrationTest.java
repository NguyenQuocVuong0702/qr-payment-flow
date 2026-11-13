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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.ipn.*;
import vn.vnpay.qr.gateway.service.PaymentExpiryJob;
import vn.vnpay.qr.merchant.client.GatewayPaymentClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:merchant-b7;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(MerchantStatusHttpIntegrationTest.TestConfig.class)
class MerchantStatusHttpIntegrationTest {
    static final Instant NOW=Instant.parse("2026-11-13T03:00:00Z");
    static final ExecutorService HTTP_THREADS=Executors.newCachedThreadPool();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final HttpServer PROXY=proxy();
    static final AtomicInteger lookups=new AtomicInteger();
    static volatile String mode="forward";
    static volatile int appPort;
    static volatile ObjectMapper jsonMapper;
    static volatile HmacSigner signer;
    static volatile CountDownLatch entered,release;
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired MutableClock clock;
    @Autowired PaymentExpiryJob expiry;
    @Autowired IpnWorker worker;
    @Autowired IpnJobStore jobs;
    @Autowired IpnAuditPayload audit;
    @Autowired IpnLogRepository logs;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean GatewayPaymentClient gateway;
    String orderId,txnRef;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.merchant.gateway.base-url",()->"http://127.0.0.1:"+PROXY.getAddress().getPort());
    }
    @BeforeEach void setup() throws Exception {
        appPort=port;jsonMapper=mapper;signer=hmac;mode="forward";lookups.set(0);clock.now.set(NOW);
        entered=new CountDownLatch(1);release=new CountDownLatch(1);
        jdbc.update("DELETE FROM ledger_entry");jdbc.update("DELETE FROM idempotency");jdbc.update("DELETE FROM payment_txn");jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM merchant WHERE merchant_id='MC002'");
        jdbc.update("UPDATE account SET balance=CASE account_no WHEN '1000001' THEN 1000000 WHEN '1000002' THEN 10000 ELSE 0 END");
        jdbc.update("UPDATE merchant SET ipn_url=? WHERE merchant_id='MC001'","http://127.0.0.1:"+PROXY.getAddress().getPort()+"/ipn");
        orderId=mapper.readTree(mvc.perform(post("/api/merchant/orders").contentType("application/json")
                .content("{\"amount\":55000,\"description\":\"Ao thun\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("orderId").asText();
        txnRef=mapper.readTree(mvc.perform(post("/api/merchant/orders/"+orderId+"/payments").contentType("application/json")
                .content("{\"expireMinutes\":1}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("txnRef").asText();
    }
    @AfterAll static void stop(){PROXY.stop(0);HTTP_THREADS.shutdownNow();}

    @Test void getIsLocalReadOnlyAndIncludesTheCurrentOrderWithoutPollingGateway() throws Exception {
        long version=version();String before=financials();
        var body=mapper.readTree(mvc.perform(get("/api/merchant/orders/"+orderId)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("WAITING_PAYMENT");assertThat(body.path("txnRef").asText()).isEqualTo(txnRef);
        assertThat(body.path("merchantId").asText()).isEqualTo("MC001");assertThat(body.path("amount").asLong()).isEqualTo(55000);
        assertThat(body.has("paidAt")).isFalse();assertThat(lookups).hasValue(0);assertThat(version()).isEqualTo(version);
        assertThat(financials()).isEqualTo(before);
    }
    @Test void lostIpnAfterThreeFailuresCanBeRecoveredWithAuthenticatedLookup() throws Exception {
        confirm("1000001");String before=financials();String snapshot=outboxBody();
        worker.runOnce();clock.now.set(NOW.plusSeconds(10));worker.runOnce();clock.now.set(NOW.plusSeconds(40));worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT state FROM ipn_outbox",String.class)).isEqualTo("EXHAUSTED");
        assertThat(state()).isEqualTo("WAITING_PAYMENT");long version=version();
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return call.callRealMethod();}).when(gateway).lookup(any());
        assertThat(sync(200).path("status").asText()).isEqualTo("PAID");
        assertThat(version()).isEqualTo(version+1);assertThat(lookups).hasValue(1);
        assertThat(sync(200).path("status").asText()).isEqualTo("PAID");assertThat(version()).isEqualTo(version+1);
        assertThat(lookups).hasValue(1);assertThat(financials()).isEqualTo(before);assertThat(outboxBody()).isEqualTo(snapshot);
        assertAuditHasNoSignature();
    }
    @Test void lookupFailedPaymentUpdatesMerchantWithoutTouchingMoney() throws Exception {
        confirm("1000002");String before=financials();
        assertThat(sync(200).path("status").asText()).isEqualTo("PAYMENT_FAILED");assertThat(financials()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE order_id=?",Object.class,orderId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry",Long.class)).isZero();
    }
    @Test void onlyGatewayExpiredStateCanExpireTheMerchantOrder() throws Exception {
        clock.now.set(NOW.plusSeconds(60));String before=moneyAndCache();
        assertThat(sync(200).path("status").asText()).isEqualTo("WAITING_PAYMENT"); // Signed PENDING, despite local clock.
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertThat(sync(200).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ipn_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry",Long.class)).isZero();
        // Expiry legitimately changes payment status; every financial row stays unchanged.
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM account",Long.class)).isEqualTo(1010000);
        assertThat(moneyAndCache()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"PAID","PAYMENT_FAILED","EXPIRED"})
    void terminalOrUnattachedOrdersAreNotChangedOrSentToGateway(String terminal) throws Exception {
        jdbc.update("UPDATE orders SET status=? WHERE order_id=?",terminal,orderId);long version=version();mode="500";
        assertThat(sync(200).path("status").asText()).isEqualTo(terminal);
        assertThat(version()).isEqualTo(version);assertThat(lookups).hasValue(0);
    }
    @Test void createdOrderWithoutPaymentCanBeReadAndSyncIsALocalNoOp() throws Exception {
        String created=mapper.readTree(mvc.perform(post("/api/merchant/orders").contentType("application/json")
                .content("{\"amount\":55000,\"description\":\"Don moi\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("orderId").asText();
        var body=mapper.readTree(mvc.perform(get("/api/merchant/orders/"+created)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("CREATED");assertThat(body.has("txnRef")).isFalse();assertThat(body.has("paidAt")).isFalse();
        mvc.perform(post("/api/merchant/orders/"+created+"/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED"));
        assertThat(lookups).hasValue(0);
    }
    @Test void nonexistentOrAnotherMerchantsOrderCannotBeReadOrSynced() throws Exception {
        mvc.perform(get("/api/merchant/orders/absent")).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        mvc.perform(post("/api/merchant/orders/absent/sync")).andExpect(status().isNotFound());
        jdbc.update("UPDATE orders SET merchant_id='MC002' WHERE order_id=?",orderId);
        mvc.perform(get("/api/merchant/orders/"+orderId)).andExpect(status().isNotFound());sync(404);
        assertThat(lookups).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"wrong-signature","missing-signature","wrong-order","wrong-reference","wrong-amount",
            "amount-string","extra-field","missing-paidAt","missing-bankRef","missing-createdAt","unknown-status","bad-json","wallet-projection","oversized","redirect"})
    void untrustedOrMismatchedGatewayResponseCannotChangeOrder(String failure) throws Exception {
        confirm("1000001");mode=failure;long version=version();String before=financials();
        assertThat(sync(502).path("errorCode").asText()).isEqualTo("INVALID_GATEWAY_RESPONSE");
        assertThat(state()).isEqualTo("WAITING_PAYMENT");assertThat(version()).isEqualTo(version);assertThat(financials()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"500","drop","timeout","body-timeout"})
    void unavailableGatewayNeverGuessesPaymentOutcome(String failure) throws Exception {
        confirm("1000001");mode=failure;String before=financials();long version=version();
        assertThat(sync(502).path("errorCode").asText()).isEqualTo("GATEWAY_UNAVAILABLE");
        assertThat(state()).isEqualTo("WAITING_PAYMENT");assertThat(version()).isEqualTo(version);assertThat(financials()).isEqualTo(before);
    }
    @Test void signedNotFoundIsPropagatedWithoutUpdatingOrder() throws Exception {
        jdbc.update("DELETE FROM payment_txn WHERE txn_ref=?",txnRef);
        assertThat(sync(404).path("errorCode").asText()).isEqualTo("TXN_NOT_FOUND");assertThat(state()).isEqualTo("WAITING_PAYMENT");
    }
    @Test void gatewayMerchantAndWalletContractsRemainSeparateAndSigned() throws Exception {
        confirm("1000001");
        var reply=gatewayGet("MC001",hmac.sign(mapper.createObjectNode().put("merchantId","MC001").put("txnRef",txnRef),"secret-mc001"));
        assertThat(reply.statusCode()).isEqualTo(200);var body=mapper.readTree(reply.body());
        assertThat(body.size()).isEqualTo(7);assertThat(body.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(body.path("orderId").asText()).isEqualTo(orderId);assertThat(body.has("bankRef")).isTrue();assertThat(body.has("paidAt")).isTrue();
        assertThat(hmac.verify(body,"secret-mc001",reply.headers().firstValue("X-Signature").orElse(null))).isTrue();
        assertThat(gatewayGet("MC001","wrong").statusCode()).isEqualTo(401);
        jdbc.update("INSERT INTO merchant(merchant_id,name,city,mcc,secret_key,ipn_url,account_no) VALUES ('MC002','OTHER','HANOI','5499','other-key','http://localhost/ipn','9000002')");
        var foreign=gatewayGet("MC002",hmac.sign(mapper.createObjectNode().put("merchantId","MC002").put("txnRef",txnRef),"other-key"));
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(hmac.verify(mapper.readTree(foreign.body()),"other-key",foreign.headers().firstValue("X-Signature").orElse(null))).isTrue();
        var wallet=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/gateway/payments/"+txnRef))
                .header("Authorization","Bearer wallet-read-token-dev-only").GET().build(),HttpResponse.BodyHandlers.ofString());
        var projection=mapper.readTree(wallet.body());assertThat(projection.size()).isEqualTo(8);
        for(String name:List.of("orderId","bankRef","payerAccount","createdAt","paidAt","secret_key"))assertThat(projection.has(name)).isFalse();
    }
    @Test void stalePendingLookupCannotUndoAnIpnThatCommittedWhileHttpWasInFlight() throws Exception {
        mode="block";var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->sync(200));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            confirm("1000001");assertThat(receive((ObjectNode)mapper.readTree(outboxBody()))).isEqualTo("00");long version=version();
            release.countDown();assertThat(pending.get(5,TimeUnit.SECONDS).path("status").asText()).isEqualTo("PAID");
            assertThat(version()).isEqualTo(version);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"SUCCESS","FAILED"})
    void anIpnWinningDuringLookupCannotBeOverwrittenEvenByAContradictorySignedResult(String status) throws Exception {
        confirm("1000001");mode="block";var body=(ObjectNode)mapper.readTree(outboxBody());
        if(status.equals("FAILED")){body.put("status","FAILED");body.remove(List.of("bankRef","paidAt"));body.put("signature",hmac.sign(body,"secret-mc001"));}
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->sync(200));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(receive(body)).isEqualTo("00");long version=version();String expected=status.equals("SUCCESS")?"PAID":"PAYMENT_FAILED";
            release.countDown();assertThat(pending.get(5,TimeUnit.SECONDS).path("status").asText()).isEqualTo(expected);
            assertThat(state()).isEqualTo(expected);assertThat(version()).isEqualTo(version);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void ipnAndLookupRacingPerformOnlyOneOrderUpdate() throws Exception {
        confirm("1000001");var body=(ObjectNode)mapper.readTree(outboxBody());String before=financials();long version=version();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var sync=pool.submit(()->{start.await();return sync(200);});var ipn=pool.submit(()->{start.await();return receive(body);});start.countDown();
            assertThat(sync.get(10,TimeUnit.SECONDS).path("status").asText()).isEqualTo("PAID");
            assertThat(ipn.get(10,TimeUnit.SECONDS)).isIn("00","02");
            assertThat(state()).isEqualTo("PAID");assertThat(version()).isEqualTo(version+1);assertThat(financials()).isEqualTo(before);
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void auditIsRedactedWhileOutboxRemainsByteForByteSigned() throws Exception {
        confirm("1000001");String signed=outboxBody();String before=financials();
        worker.runOnce();assertAuditHasNoSignature();
        assertThat(outboxBody()).isEqualTo(signed);assertThat(hmac.verify(mapper.readTree(signed),"secret-mc001",mapper.readTree(signed).path("signature").asText())).isTrue();
        assertThat(financials()).isEqualTo(before);
    }
    @Test void legacyAuditRedactionPreservesHistoryAndConcurrentCompletionDoesNotRestoreSignature() throws Exception {
        confirm("1000001");var claim=jobs.claimNext();String signed=outboxBody();String before=financials();
        jdbc.update("UPDATE ipn_log SET request_body=? WHERE txn_ref=?",signed,txnRef); // Legacy B6 row.
        var loaded=new CountDownLatch(1);var finish=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var completion=pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{
                var row=logs.findByTxnRefAndAttempt(txnRef,1).orElseThrow();loaded.countDown();
                try{if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");}catch(InterruptedException ex){throw new IllegalStateException(ex);}
                row.complete(new IpnDeliveryResult(true,"00",200,null),NOW.plusSeconds(1));return null;
            }));
            assertThat(loaded.await(5,TimeUnit.SECONDS)).isTrue();
            var history=jdbc.queryForList("SELECT id,txn_ref,attempt,response_code,sent_at FROM ipn_log");
            audit.run(new DefaultApplicationArguments());assertAuditHasNoSignature();
            assertThat(jdbc.queryForList("SELECT id,txn_ref,attempt,response_code,sent_at FROM ipn_log")).isEqualTo(history);
            finish.countDown();completion.get(5,TimeUnit.SECONDS);assertAuditHasNoSignature();
            assertThat(jdbc.queryForObject("SELECT response_code FROM ipn_log",String.class)).isEqualTo("00");
            jobs.complete(claim,new IpnDeliveryResult(true,"00",200,null));
            assertThat(outboxBody()).isEqualTo(signed);assertThat(financials()).isEqualTo(before);
        } finally {finish.countDown();pool.shutdownNow();}
    }

    private void confirm(String account) throws Exception {
        var body=mapper.createObjectNode().put("payerAccount",account).put("amount",55000).put("requestId",UUID.randomUUID().toString());
        mvc.perform(post("/api/gateway/payments/"+txnRef+"/confirm").contentType("application/json").content(body.toString())
                .header("X-Wallet-Signature",hmac.sign(body.deepCopy().put("txnRef",txnRef),"wallet-payment-secret-dev-only"))).andExpect(status().isOk());
    }
    private JsonNode sync(int expected) throws Exception {return mapper.readTree(mvc.perform(post("/api/merchant/orders/"+orderId+"/sync"))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());}
    private String receive(ObjectNode body) throws Exception {return mapper.readTree(mvc.perform(post("/api/merchant/ipn").contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("code").asText();}
    private HttpResponse<String> gatewayGet(String merchant,String signature) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/gateway/payments/"+txnRef))
                .header("X-Merchant-Id",merchant).header("X-Signature",signature).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertAuditHasNoSignature() throws Exception {
        for(String body:jdbc.queryForList("SELECT request_body FROM ipn_log",String.class)) {
            assertThat(mapper.readTree(body).has("signature")).isFalse();
            assertThat(body).doesNotContain(mapper.readTree(outboxBody()).path("signature").asText());
        }
    }
    private String state(){return jdbc.queryForObject("SELECT status FROM orders WHERE order_id=?",String.class,orderId);}
    private long version(){return jdbc.queryForObject("SELECT version FROM orders WHERE order_id=?",Long.class,orderId);}
    private String outboxBody(){return jdbc.queryForObject("SELECT request_body FROM ipn_outbox WHERE txn_ref=?",String.class,txnRef);}
    private String moneyAndCache(){return jdbc.queryForList("SELECT * FROM account ORDER BY account_no").toString()
            +jdbc.queryForList("SELECT * FROM ledger_entry ORDER BY id")+jdbc.queryForList("SELECT request_id,txn_ref,payer_account,amount,http_status,CAST(response_body AS VARCHAR) AS response_body,created_at,version FROM idempotency ORDER BY request_id")
            ;}
    private String financials(){return moneyAndCache()+jdbc.queryForList("SELECT txn_ref,status,bank_ref,payer_account,paid_at,version FROM payment_txn ORDER BY txn_ref");}
    static HttpServer proxy() {
        try {var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(HTTP_THREADS);
            server.createContext("/ipn",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(500,-1);exchange.close();});
            server.createContext("/api/gateway/payments",exchange->{try {
                boolean get=exchange.getRequestMethod().equals("GET");String current=mode;
                if(get)lookups.incrementAndGet();
                if(get&&current.equals("drop"))return;
                if(get&&current.equals("timeout")){Thread.sleep(6000);return;}
                if(get&&current.equals("500")){exchange.sendResponseHeaders(500,-1);return;}
                if(get&&current.equals("body-timeout")){exchange.sendResponseHeaders(200,100);Thread.sleep(6000);return;}
                if(get&&current.equals("redirect")){exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+exchange.getRequestURI().getPath());exchange.sendResponseHeaders(302,-1);return;}
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+exchange.getRequestURI().getPath())).header("Content-Type","application/json");
                for(String name:List.of("X-Merchant-Id","X-Signature")){var value=exchange.getRequestHeaders().getFirst(name);if(value!=null)request.header(name,value);}
                if(get)request.GET();else request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                var reply=HTTP.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());byte[] body=reply.body();String signature=reply.headers().firstValue("X-Signature").orElse("");
                if(get) {
                    var json=(ObjectNode)jsonMapper.readTree(body);
                    switch(current) {
                        case "wrong-order" -> json.put("orderId","OTHER_ORDER");
                        case "wrong-reference" -> json.put("txnRef","TX99999999");
                        case "wrong-amount" -> json.put("amount",1000);
                        case "amount-string" -> json.put("amount","55000");
                        case "extra-field" -> json.put("merchantId","MC001");
                        case "missing-paidAt" -> json.remove("paidAt");
                        case "missing-bankRef" -> json.remove("bankRef");
                        case "missing-createdAt" -> json.remove("createdAt");
                        case "unknown-status" -> json.put("status","OTHER");
                        case "oversized" -> json.put("bankRef","B".repeat(20000));
                        case "wallet-projection" -> {json.remove("orderId");json.put("merchantName","SHOP ABC").put("currency","VND");}
                        default -> { }
                    }
                    body=jsonMapper.writeValueAsBytes(json);signature=signer.sign(json,"secret-mc001");
                    if(current.equals("wrong-signature"))signature="0".repeat(64);
                    if(current.equals("bad-json"))body="{".getBytes(StandardCharsets.UTF_8);
                    if(current.equals("block")){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");}
                    if(!current.equals("missing-signature"))exchange.getResponseHeaders().set("X-Signature",signature);
                }else exchange.getResponseHeaders().set("X-Signature",signature);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(reply.statusCode(),body.length);exchange.getResponseBody().write(body);
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();return server;
        }catch(IOException ex){throw new ExceptionInInitializerError(ex);}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestConfig {@Bean @Primary MutableClock b7MerchantClock(){return new MutableClock();}}
}

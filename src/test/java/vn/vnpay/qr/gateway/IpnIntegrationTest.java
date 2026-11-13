package vn.vnpay.qr.gateway;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jakarta.persistence.EntityManager;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.ipn.*;
import vn.vnpay.qr.merchant.service.MerchantPaymentStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:ipn-b6;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(IpnIntegrationTest.TestConfig.class)
class IpnIntegrationTest {
    static final Instant NOW=Instant.parse("2026-11-13T02:00:00Z");
    static final ExecutorService HTTP_THREADS=Executors.newCachedThreadPool();
    static final HttpServer MERCHANT=server();
    static final AtomicInteger calls=new AtomicInteger();
    static volatile String mode="forward";
    static volatile int appPort;
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MutableClock clock;
    @Autowired IpnWorker worker;
    @Autowired IpnJobStore store;
    @Autowired MerchantPaymentStore merchantOrders; // Fixture preparation only; production Gateway does not import Merchant.
    @MockitoSpyBean IpnHttpClient http;
    @MockitoSpyBean IpnOutboxRepository outbox;
    String orderId;
    String txnRef;
    String requestId;

    @BeforeEach void clean() throws Exception {
        appPort=port; mode="forward"; calls.set(0); entered=new CountDownLatch(1); release=new CountDownLatch(1); clock.now.set(NOW);
        jdbc.update("DELETE FROM ledger_entry");jdbc.update("DELETE FROM idempotency");jdbc.update("DELETE FROM payment_txn");jdbc.update("DELETE FROM orders");
        jdbc.update("UPDATE account SET balance=CASE account_no WHEN '1000001' THEN 1000000 WHEN '1000002' THEN 10000 ELSE 0 END");
        jdbc.update("UPDATE merchant SET ipn_url=?,account_no='9000001' WHERE merchant_id='MC001'","http://127.0.0.1:"+MERCHANT.getAddress().getPort()+"/ipn");
        var order=mvc.perform(post("/api/merchant/orders").contentType("application/json").content("{\"amount\":55000,\"description\":\"Ao thun\"}"))
                .andExpect(status().isCreated()).andReturn();
        orderId=mapper.readTree(order.getResponse().getContentAsString()).path("orderId").asText();
        var body=mapper.createObjectNode().put("merchantId","MC001").put("orderId",orderId).put("amount",55000).put("description","Ao thun").put("expireMinutes",15);
        body.put("signature",hmac.sign(body,"secret-mc001"));
        var payment=mvc.perform(post("/api/gateway/payments").contentType("application/json").content(body.toString())).andExpect(status().isCreated()).andReturn();
        txnRef=mapper.readTree(payment.getResponse().getContentAsString()).path("txnRef").asText();
        merchantOrders.attach(orderId,txnRef);requestId=UUID.randomUUID().toString();
    }
    @AfterAll static void stop(){MERCHANT.stop(0);HTTP_THREADS.shutdownNow();}

    @Test void successfulIpnIsDurableSignedAndUsesHttpOutsideTransaction() throws Exception {
        pay("1000001",200); String finances=finances();
        assertThat(job()).isEqualTo("READY");assertThat(count("ipn_outbox")).isEqualTo(1);assertThat(count("ipn_log")).isZero();
        assertThat(orderState()).isEqualTo("WAITING_PAYMENT");
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return call.callRealMethod();}).when(http).send(any());
        assertThat(worker.runOnce()).isTrue(); assertThat(orderState()).isEqualTo("PAID");assertThat(job()).isEqualTo("DELIVERED");
        assertThat(code(1)).isEqualTo("00");assertThat(calls).hasValue(1);assertThat(finances()).isEqualTo(finances);
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE order_id=?",java.time.OffsetDateTime.class,orderId).toInstant()).isEqualTo(NOW);
        assertThat(worker.runOnce()).isFalse();
    }
    @Test void failedIpnOmitsUnassignedFieldsAndMarksPaymentFailed() throws Exception {
        pay("1000002",200);var body=payload();
        assertThat(body.path("status").asText()).isEqualTo("FAILED");assertThat(body.has("bankRef")).isFalse();assertThat(body.has("paidAt")).isFalse();
        assertThat(hmac.verify(body,"secret-mc001",body.path("signature").asText())).isTrue();
        assertThat(worker.runOnce()).isTrue();assertThat(orderState()).isEqualTo("PAYMENT_FAILED");assertThat(count("ledger_entry")).isZero();
    }
    @Test void walletReplayDoesNotCreateAnotherJobOrChangeMoney() throws Exception {
        pay("1000001",200);String before=finances();String snapshot=payload().toString();pay("1000001",200);
        assertThat(count("ipn_outbox")).isEqualTo(1);assertThat(payload().toString()).isEqualTo(snapshot);assertThat(finances()).isEqualTo(before);
    }
    @Test void explicitNullOptionalFailedFieldsHaveTheSameCanonicalSignature() throws Exception {
        pay("1000002",200);var body=payload();String signature=body.path("signature").asText();
        body.putNull("bankRef").putNull("paidAt");
        assertThat(hmac.sign(body,"secret-mc001")).isEqualTo(signature);
        assertThat(receive(body)).isEqualTo("00");assertThat(orderState()).isEqualTo("PAYMENT_FAILED");
    }
    @Test void outboxFailureRollsBackTheAlreadyFlushedBankAndIdempotency() throws Exception {
        doAnswer(call->{IpnOutbox row=call.getArgument(0);em.persist(row);em.flush();throw new IllegalStateException("Injected outbox failure");})
                .when(outbox).saveAndFlush(any(IpnOutbox.class));
        pay("1000001",500);
        assertThat(count("ledger_entry")).isZero();assertThat(count("idempotency")).isZero();assertThat(count("ipn_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM account WHERE account_no='1000001'",Long.class)).isEqualTo(1000000);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,txnRef)).isEqualTo("PENDING");
        reset(outbox);pay("1000001",200);assertThat(count("ipn_outbox")).isEqualTo(1);
    }
    @Test void scenario9TwoHttp500ThenSuccessHasExactlyThreeLogs() throws Exception {
        pay("1000001",200);String before=finances();mode="two-500";
        assertThat(worker.runOnce()).isTrue();assertThat(code(1)).isEqualTo("HTTP_500");assertThat(worker.runOnce()).isFalse();
        clock.now.set(NOW.plusSeconds(9));assertThat(worker.runOnce()).isFalse();
        clock.now.set(NOW.plusSeconds(10));assertThat(worker.runOnce()).isTrue();assertThat(code(2)).isEqualTo("HTTP_500");
        clock.now.set(NOW.plusSeconds(39));assertThat(worker.runOnce()).isFalse();
        clock.now.set(NOW.plusSeconds(40));assertThat(worker.runOnce()).isTrue();
        assertThat(job()).isEqualTo("DELIVERED");assertThat(orderState()).isEqualTo("PAID");
        assertThat(count("ipn_log")).isEqualTo(3);assertThat(calls).hasValue(3);assertThat(code(3)).isEqualTo("00");
        assertThat(worker.runOnce()).isFalse();assertThat(finances()).isEqualTo(before);
    }
    @Test void scenario10InvalidSignatureReturns97AndNeverChangesOrder() throws Exception {
        pay("1000001",200);var body=payload();body.put("signature","0".repeat(64));assertThat(receive(body)).isEqualTo("97");
        body.put("orderId","missing").put("amount",1000L);assertThat(receive(body)).isEqualTo("97");
        assertThat(orderState()).isEqualTo("WAITING_PAYMENT");
    }
    @Test void validatesOrderAmountReferenceAndMerchantBindingInOrder() throws Exception {
        pay("1000001",200);var body=payload();body.put("orderId","missing");assertThat(receive(signed(body,"secret-mc001"))).isEqualTo("01");
        body=payload();body.put("amount",1000);assertThat(receive(signed(body,"secret-mc001"))).isEqualTo("04");
        body=payload();body.put("txnRef","TX99999999");assertThat(receive(signed(body,"secret-mc001"))).isEqualTo("01");
        body=payload();body.put("amount",1000).put("txnRef","TX99999999");assertThat(receive(signed(body,"secret-mc001"))).isEqualTo("04");
        assertThat(receive(signed(payload(),"secret-mc002"))).isEqualTo("97");
        jdbc.update("UPDATE orders SET merchant_id='MC002' WHERE order_id=?",orderId);
        assertThat(receive(payload())).isEqualTo("01");assertThat(orderState()).isEqualTo("WAITING_PAYMENT");
    }
    @Test void paidOrderCannotBeReversedByReplayOrContradictoryFailedIpn() throws Exception {
        pay("1000001",200);assertThat(receive(payload())).isEqualTo("00");
        long version=orderVersion();assertThat(receive(payload())).isEqualTo("02");
        var failed=payload();failed.put("status","FAILED");failed.remove(List.of("bankRef","paidAt"));
        assertThat(receive(signed(failed,"secret-mc001"))).isEqualTo("02");assertThat(orderState()).isEqualTo("PAID");assertThat(orderVersion()).isEqualTo(version);
    }
    @Test void failedOrderCannotBeReversedByContradictorySuccess() throws Exception {
        pay("1000002",200);assertThat(receive(payload())).isEqualTo("00");
        var success=payload().put("status","SUCCESS").put("bankRef","BK20261113000001").put("paidAt","2026-11-13T09:00:00+07:00");
        assertThat(receive(signed(success,"secret-mc001"))).isEqualTo("02");assertThat(orderState()).isEqualTo("PAYMENT_FAILED");
    }
    @Test void concurrentMerchantIpnsHaveOneUpdateAndOneReplay() throws Exception {
        pay("1000001",200);long version=orderVersion();String body=payload().toString();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<String> action=()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException();return receive((ObjectNode)mapper.readTree(body));};
            var a=executor.submit(action);var b=executor.submit(action);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("00","02");
        } finally {start.countDown();executor.shutdownNow();}
        assertThat(orderVersion()).isEqualTo(version+1);
    }
    @Test void merchant02StopsWorkerRetry() throws Exception {
        pay("1000001",200);assertThat(receive(payload())).isEqualTo("00");
        worker.runOnce();assertThat(code(1)).isEqualTo("02");assertThat(job()).isEqualTo("DELIVERED");assertThat(worker.runOnce()).isFalse();assertThat(calls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings={"500","97","drop","bad-json","redirect"})
    void retriesStopAtThreeAndRemainExhaustedWithoutMovingMoney(String failure) throws Exception {
        pay("1000001",200);String before=finances();mode=failure;
        worker.runOnce();clock.now.set(NOW.plusSeconds(10));worker.runOnce();clock.now.set(NOW.plusSeconds(40));worker.runOnce();
        assertThat(job()).isEqualTo("EXHAUSTED");assertThat(count("ipn_log")).isEqualTo(3);assertThat(calls).hasValue(3);
        clock.now.set(NOW.plusSeconds(10000));assertThat(worker.runOnce()).isFalse();
        assertThat(orderState()).isEqualTo("WAITING_PAYMENT");assertThat(finances()).isEqualTo(before);
    }
    @Test void readTimeoutIsLoggedAndRetryCanSucceed() throws Exception {
        pay("1000001",200);mode="timeout";worker.runOnce();assertThat(code(1)).isEqualTo("TIMEOUT");
        mode="forward";clock.now.set(NOW.plusSeconds(10));worker.runOnce();
        assertThat(job()).isEqualTo("DELIVERED");assertThat(orderState()).isEqualTo("PAID");assertThat(calls).hasValue(2);
    }
    @Test void lostAckAfterMerchantCommitRetriesAndStopsOn02() throws Exception {
        pay("1000001",200);String before=finances();mode="drop-after-process";worker.runOnce();
        assertThat(orderState()).isEqualTo("PAID");assertThat(job()).isEqualTo("READY");assertThat(calls).hasValue(1);
        mode="forward";clock.now.set(NOW.plusSeconds(10));worker.runOnce();
        assertThat(code(2)).isEqualTo("02");assertThat(job()).isEqualTo("DELIVERED");assertThat(finances()).isEqualTo(before);
    }
    @Test void abandonedLeaseBeforeSendIsRecoveredWithFencedLateCompletion() throws Exception {
        pay("1000001",200);var first=store.claimNext();assertThat(first).isNotNull();assertThat(calls).hasValue(0);
        clock.now.set(NOW.plusSeconds(60));assertThat(store.claimNext()).isNull();assertThat(code(1)).isEqualTo("UNKNOWN_AFTER_CRASH");
        clock.now.set(NOW.plusSeconds(70));var second=store.claimNext();assertThat(second.attempt()).isEqualTo(2);
        store.complete(first,new IpnDeliveryResult(true,"00",200,null));assertThat(job()).isEqualTo("LEASED");
        store.complete(second,http.send(second));assertThat(job()).isEqualTo("DELIVERED");assertThat(orderState()).isEqualTo("PAID");
    }
    @Test void abandonedLeaseAfterMerchantCommitRecoversAndGets02() throws Exception {
        pay("1000001",200);var first=store.claimNext();assertThat(http.send(first).delivered()).isTrue();
        assertThat(orderState()).isEqualTo("PAID"); // Simulated crash: never record the ACK.
        clock.now.set(NOW.plusSeconds(60));store.claimNext();clock.now.set(NOW.plusSeconds(70));worker.runOnce();
        assertThat(code(1)).isEqualTo("UNKNOWN_AFTER_CRASH");assertThat(code(2)).isEqualTo("02");assertThat(job()).isEqualTo("DELIVERED");
    }
    @Test void threeAbandonedClaimsExhaustThePersistedBudgetWithoutAFourthSend() throws Exception {
        pay("1000001",200);String before=finances();long elapsed=0;
        for(int attempt=1;attempt<=3;attempt++) {
            assertThat(store.claimNext().attempt()).isEqualTo(attempt);
            elapsed+=60;clock.now.set(NOW.plusSeconds(elapsed));
            assertThat(store.claimNext()).isNull();assertThat(code(attempt)).isEqualTo("UNKNOWN_AFTER_CRASH");
            elapsed+=attempt==1?10:30;clock.now.set(NOW.plusSeconds(elapsed));
        }
        assertThat(job()).isEqualTo("EXHAUSTED");assertThat(count("ipn_log")).isEqualTo(3);
        clock.now.set(NOW.plusSeconds(10000));assertThat(worker.runOnce()).isFalse();assertThat(calls).hasValue(0);
        assertThat(finances()).isEqualTo(before);
    }
    @Test void twoWorkersCannotSendTheSameJobConcurrently() throws Exception {
        pay("1000001",200);mode="block";var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->worker.runOnce());assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->worker.runOnce());assertThat(second.get(5,TimeUnit.SECONDS)).isFalse();
            assertThat(calls).hasValue(1);release.countDown();assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();executor.shutdownNow();}
        assertThat(job()).isEqualTo("DELIVERED");assertThat(count("ipn_log")).isEqualTo(1);
    }
    @Test void invalidSignedResultDoesNotCorruptOrder() throws Exception {
        pay("1000001",200);var body=payload().put("status","PENDING");signed(body,"secret-mc001");
        mvc.perform(post("/api/merchant/ipn").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        assertThat(orderState()).isEqualTo("WAITING_PAYMENT");
    }
    private void pay(String payer,int status) throws Exception {
        var body=mapper.createObjectNode().put("payerAccount",payer).put("amount",55000).put("requestId",requestId);
        mvc.perform(post("/api/gateway/payments/"+txnRef+"/confirm").contentType("application/json")
                .header("X-Wallet-Signature",hmac.sign(body.deepCopy().put("txnRef",txnRef),"wallet-payment-secret-dev-only")).content(body.toString()))
                .andExpect(status().is(status));
    }
    private ObjectNode payload() throws Exception {return (ObjectNode)mapper.readTree(jdbc.queryForObject("SELECT request_body FROM ipn_outbox WHERE txn_ref=?",String.class,txnRef));}
    private ObjectNode signed(ObjectNode body,String key){return body.put("signature",hmac.sign(body,key));}
    private String receive(ObjectNode body)throws Exception {
        var result=mvc.perform(post("/api/merchant/ipn").contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }
    private String job(){return jdbc.queryForObject("SELECT state FROM ipn_outbox WHERE txn_ref=?",String.class,txnRef);}
    private String code(int attempt){return jdbc.queryForObject("SELECT response_code FROM ipn_log WHERE txn_ref=? AND attempt=?",String.class,txnRef,attempt);}
    private String orderState(){return jdbc.queryForObject("SELECT status FROM orders WHERE order_id=?",String.class,orderId);}
    private long orderVersion(){return jdbc.queryForObject("SELECT version FROM orders WHERE order_id=?",Long.class,orderId);}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private String finances(){return jdbc.queryForList("SELECT account_no,balance FROM account ORDER BY account_no").toString()
            +jdbc.queryForList("SELECT bank_ref,account_no,direction,amount FROM ledger_entry ORDER BY id").toString()
            +jdbc.queryForList("SELECT txn_ref,bank_ref,status FROM payment_txn ORDER BY txn_ref").toString();}
    private static HttpServer server(){
        try {var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(HTTP_THREADS);
            server.createContext("/ipn",exchange->{try {
                int call=calls.incrementAndGet();String current=mode;byte[] body=exchange.getRequestBody().readAllBytes();
                if(current.equals("block")){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timeout");}
                if(current.equals("drop"))return;
                if(current.equals("timeout")){Thread.sleep(6000);return;}
                if(current.equals("500")||current.equals("two-500")&&call<=2){exchange.sendResponseHeaders(500,-1);return;}
                if(current.equals("redirect")){exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/ipn");exchange.sendResponseHeaders(302,-1);return;}
                byte[] response;int status=200;
                if(current.equals("97"))response="{\"code\":\"97\",\"message\":\"Invalid signature\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(current.equals("bad-json"))response="{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else {
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+"/api/merchant/ipn")).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var reply=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofByteArray());response=reply.body();status=reply.statusCode();
                    if(current.equals("drop-after-process"))return;
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();return server;
        }catch(IOException ex){throw new ExceptionInInitializerError(ex);}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestConfig {@Bean @Primary MutableClock ipnTestClock(){return new MutableClock();}}
}

package vn.vnpay.qr.gateway;

import java.io.IOException;
import java.math.BigDecimal;
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
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.event.*;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.api.ConfirmPaymentRequest;
import vn.vnpay.qr.gateway.bank.*;
import vn.vnpay.qr.gateway.domain.PaymentStatus;
import vn.vnpay.qr.gateway.repository.IdempotencyRepository;
import vn.vnpay.qr.gateway.service.PaymentCompleted;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:gateway-b5;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(ConfirmationIntegrationTest.TestConfig.class)
class ConfirmationIntegrationTest {
    static final String SECRET="wallet-payment-secret-dev-only";
    static final Instant NOW=Instant.parse("2026-11-12T02:00:00Z");
    static final ExecutorService PROXY_THREADS=Executors.newCachedThreadPool();
    static final HttpServer PROXY=proxy();
    static volatile int appPort;
    static volatile boolean dropResult;
    static final AtomicInteger orderNumbers=new AtomicInteger();
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired BankTransferService bank;
    @Autowired MutableClock clock;
    @Autowired CommittedEvents committedEvents;
    @MockitoSpyBean AccountRepository accounts;
    @MockitoSpyBean LedgerEntryRepository ledger;
    @MockitoSpyBean IdempotencyRepository requests;

    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("app.wallet.gateway.base-url",()->"http://127.0.0.1:"+PROXY.getAddress().getPort());
        registry.add("app.merchant.gateway.base-url",()->"http://127.0.0.1:"+PROXY.getAddress().getPort());
    }
    @BeforeEach void clean() {
        appPort=port; dropResult=false; clock.now.set(NOW); committedEvents.events.clear();
        jdbc.update("DELETE FROM ledger_entry"); jdbc.update("DELETE FROM idempotency");
        jdbc.update("DELETE FROM payment_txn"); jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM account WHERE account_no NOT IN ('1000001','1000002','9000001')");
        jdbc.update("UPDATE account SET balance=CASE account_no WHEN '1000001' THEN 1000000 WHEN '1000002' THEN 10000 ELSE 0 END");
        jdbc.update("UPDATE wallet_user SET failed_pin_attempts=0,pin_locked_until=NULL");
    }
    @AfterAll static void stop() { PROXY.stop(0); PROXY_THREADS.shutdownNow(); }

    @Test void successCommitsBalancesDoubleEntryAndExactReplayOnce() throws Exception {
        String ref=create(55000); String id=id(); BigDecimal total=total();
        var first=confirm(ref,"1000001",55000,id,200);
        var body=mapper.readTree(first);
        assertThat(body.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(body.path("bankRef").asText()).matches("BK20261112[0-9]{6,}");
        assertThat(body.path("paidAt").asText()).isEqualTo("2026-11-12T09:00:00+07:00");
        assertThat(balance("1000001")).isEqualTo(945000); assertThat(balance("9000001")).isEqualTo(55000);
        clock.now.set(NOW.plusSeconds(3600));
        assertThat(confirm(ref,"1000001",55000,id,200)).isEqualTo(first);
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(count("idempotency")).isEqualTo(1);
        assertThat(committedEvents.events).hasSize(1); invariants(total);
        assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency WHERE request_id=?",String.class,UUID.fromString(id))).isEqualTo(first);
    }

    @Test void insufficientFundsReturnsFailedWithNoMoneyOrLedgerAndReplaysExactly() throws Exception {
        String ref=create(55000); String id=id(); BigDecimal total=total();
        String first=confirm(ref,"1000002",55000,id,200);
        var body=mapper.readTree(first);
        assertThat(body.path("status").asText()).isEqualTo("FAILED");
        assertThat(body.path("errorCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(body.has("bankRef")).isFalse(); assertThat(body.has("paidAt")).isFalse();
        assertThat(confirm(ref,"1000002",55000,id,200)).isEqualTo(first);
        assertThat(balance("1000002")).isEqualTo(10000); assertThat(balance("9000001")).isZero();
        assertThat(state(ref)).isEqualTo("FAILED"); assertThat(count("ledger_entry")).isZero(); invariants(total);
    }

    @Test void expiresAndCommitsExpiredStateAndStableErrorResponse() throws Exception {
        String ref=create(55000); String id=id(); clock.now.set(NOW.plusSeconds(900));
        String result=confirm(ref,"1000001",55000,id,422);
        assertCode(result,"TXN_EXPIRED"); assertThat(state(ref)).isEqualTo("EXPIRED");
        clock.now.set(NOW.plusSeconds(1800));
        assertThat(confirm(ref,"1000001",55000,id,422)).isEqualTo(result);
        assertThat(count("ledger_entry")).isZero(); assertThat(committedEvents.events).isEmpty();
    }

    @Test void missingTransactionAmountMismatchPaidTxnAndMissingAccount() throws Exception {
        assertCode(confirm("TX99999999","1000001",55000,id(),404),"TXN_NOT_FOUND");
        String ref=create(55000);
        assertCode(confirm(ref,"1000001",50000,id(),422),"AMOUNT_MISMATCH");
        assertThat(state(ref)).isEqualTo("PENDING");
        confirm(ref,"1000001",55000,id(),200);
        assertCode(confirm(ref,"1000001",55000,id(),422),"TXN_NOT_PAYABLE");
        String second=create(55000); BigDecimal total=total();
        assertCode(confirm(second,"7777777",55000,id(),404),"ACCOUNT_NOT_FOUND");
        assertThat(state(second)).isEqualTo("FAILED"); assertThat(count("ledger_entry")).isEqualTo(2); invariants(total);
    }

    @Test void missingDestinationAndSelfTransferDoNotChangeMoney() throws Exception {
        String ref=create(55000); BigDecimal total=total();
        assertCode(confirm(ref,"9000001",55000,id(),422),"INVALID_TRANSFER");
        assertThat(state(ref)).isEqualTo("PENDING");
        jdbc.update("UPDATE merchant SET account_no='7777777' WHERE merchant_id='MC001'");
        try { assertCode(confirm(ref,"1000001",55000,id(),404),"ACCOUNT_NOT_FOUND"); }
        finally { jdbc.update("UPDATE merchant SET account_no='9000001' WHERE merchant_id='MC001'"); }
        assertThat(count("ledger_entry")).isZero(); invariants(total);
    }

    @Test void idempotencyKeyCannotBeReboundToAmountSourceOrTransaction() throws Exception {
        String ref=create(55000); String other=create(55000); String key=id();
        confirm(ref,"1000001",55000,key,200);
        assertCode(confirm(ref,"1000001",50000,key,409),"IDEMPOTENCY_CONFLICT");
        assertCode(confirm(ref,"1000002",55000,key,409),"IDEMPOTENCY_CONFLICT");
        assertCode(confirm(other,"1000001",55000,key,409),"IDEMPOTENCY_CONFLICT");
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(state(other)).isEqualTo("PENDING");
    }

    @Test void unauthenticatedReadCredentialAndTamperedSourceCannotConfirm() throws Exception {
        String ref=create(55000); var body=body("1000001",55000,id());
        mvc.perform(post(path(ref)).contentType("application/json").content(body.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post(path(ref)).header("Authorization","Bearer wallet-read-token-dev-only")
                .contentType("application/json").content(body.toString())).andExpect(status().isUnauthorized());
        String signature=sign(ref,body); body.put("payerAccount","1000002");
        mvc.perform(post(path(ref)).header("X-Wallet-Signature",signature).contentType("application/json").content(body.toString()))
                .andExpect(status().isUnauthorized());
        assertThat(count("idempotency")).isZero(); assertThat(count("ledger_entry")).isZero();
    }

    @Test void simultaneousSameUuidReturnsOneExactResult() throws Exception {
        String ref=create(55000); String key=id(); BigDecimal total=total();
        var results=parallel(Collections.nCopies(8,()->raw(ref,"1000001",55000,key)));
        assertThat(results.stream().map(Reply::status).toList()).containsOnly(200);
        assertThat(results.stream().map(Reply::body).distinct().count()).isEqualTo(1);
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(count("idempotency")).isEqualTo(1); invariants(total);
    }

    @Test void simultaneousDifferentUuidsOnSameTxnHaveOneSuccess() throws Exception {
        String ref=create(55000); BigDecimal total=total();
        var results=parallel(List.of(()->raw(ref,"1000001",55000,id()),()->raw(ref,"1000001",55000,id())));
        assertThat(results.stream().map(Reply::status).toList()).containsExactlyInAnyOrder(200,422);
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(count("idempotency")).isEqualTo(2); invariants(total);
    }

    @Test void simultaneousSameUuidOnDifferentTransactionsCannotDoubleSpend() throws Exception {
        String first=create(55000),second=create(55000),key=id();
        var results=parallel(List.of(()->raw(first,"1000001",55000,key),()->raw(second,"1000001",55000,key)));
        assertThat(results.stream().map(Reply::status).toList()).containsExactlyInAnyOrder(200,409);
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(count("idempotency")).isEqualTo(1);
        assertThat(balance("1000001")).isEqualTo(945000);
    }

    @Test void concurrentTransactionsCannotOverdrawOneSource() throws Exception {
        jdbc.update("UPDATE account SET balance=80000 WHERE account_no='1000001'");
        String first=create(55000),second=create(55000); BigDecimal total=total();
        var results=parallel(List.of(()->raw(first,"1000001",55000,id()),()->raw(second,"1000001",55000,id())));
        var statuses=new ArrayList<String>(); for(var reply:results) statuses.add(mapper.readTree(reply.body()).path("status").asText());
        assertThat(statuses).containsExactlyInAnyOrder("SUCCESS","FAILED");
        assertThat(balance("1000001")).isEqualTo(25000); assertThat(balance("9000001")).isEqualTo(55000); invariants(total);
    }

    @Test void differentSourcesCreditingSameMerchantDoNotLoseUpdates() throws Exception {
        String first=create(55000),second=create(5000); BigDecimal total=total();
        var results=parallel(List.of(()->raw(first,"1000001",55000,id()),()->raw(second,"1000002",5000,id())));
        assertThat(results.stream().map(Reply::status).toList()).containsOnly(200);
        assertThat(balance("9000001")).isEqualTo(60000); assertThat(count("ledger_entry")).isEqualTo(4); invariants(total);
    }

    @Test void manyTransactionsKeepTotalAndStopExactlyAtAvailableBalance() throws Exception {
        List<Callable<Reply>> actions=new ArrayList<>(); BigDecimal total=total();
        for(int i=0;i<6;i++){String ref=create(200000);actions.add(()->raw(ref,"1000001",200000,id()));}
        var results=parallel(actions); int success=0;
        for(var result:results) if(mapper.readTree(result.body()).path("status").asText().equals("SUCCESS")) success++;
        assertThat(success).isEqualTo(5); assertThat(balance("1000001")).isZero();
        assertThat(balance("9000001")).isEqualTo(1000000); assertThat(count("ledger_entry")).isEqualTo(10); invariants(total);
    }

    @ParameterizedTest @ValueSource(strings={"debit","credit","ledger1","ledger2","response"})
    void faultsAfterActualDatabaseWritesRollBackEveryTableAndSameUuidCanRetry(String stage) throws Exception {
        String ref=create(55000),key=id(); BigDecimal total=total(); var hits=new AtomicInteger();
        if(stage.equals("debit")||stage.equals("credit")) {
            int failAt=stage.equals("debit")?1:2;
            doAnswer(call->{entityManager.flush(); if(hits.incrementAndGet()==failAt)throw new IllegalStateException("Injected write fault");return null;}).when(accounts).flush();
        } else if(stage.startsWith("ledger")) {
            int failAt=stage.equals("ledger1")?1:2;
            doAnswer(call->{LedgerEntry entry=call.getArgument(0);entityManager.persist(entry);entityManager.flush();
                if(hits.incrementAndGet()==failAt)throw new IllegalStateException("Injected ledger fault");return entry;}).when(ledger).saveAndFlush(any(LedgerEntry.class));
        } else {
            doAnswer(call->{entityManager.flush();throw new IllegalStateException("Injected result fault");}).when(requests).flush();
        }
        confirm(ref,"1000001",55000,key,500);
        assertThat(balance("1000001")).isEqualTo(1000000); assertThat(balance("9000001")).isZero();
        assertThat(count("ledger_entry")).isZero(); assertThat(count("idempotency")).isZero();
        assertThat(state(ref)).isEqualTo("PENDING"); assertThat(committedEvents.events).isEmpty(); invariants(total);
        reset(accounts,ledger,requests);
        confirm(ref,"1000001",55000,key,200); assertThat(count("ledger_entry")).isEqualTo(2); invariants(total);
    }

    @Test void creditOverflowRollsBackAndBankCannotRunWithoutGatewayTransaction() throws Exception {
        jdbc.update("UPDATE account SET balance=? WHERE account_no='9000001'",Long.MAX_VALUE);
        String ref=create(1000); confirm(ref,"1000001",1000,id(),500);
        assertThat(balance("9000001")).isEqualTo(Long.MAX_VALUE); assertThat(balance("1000001")).isEqualTo(1000000);
        assertThat(count("ledger_entry")).isZero(); assertThat(count("idempotency")).isZero(); assertThat(state(ref)).isEqualTo("PENDING");
        assertThatThrownBy(()->bank.transfer(ref,"1000001","9000001",1000,NOW))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(PaymentStatus.SUCCESS.canTransitionTo(PaymentStatus.PENDING)).isFalse();
        assertThat(PaymentStatus.EXPIRED.canTransitionTo(PaymentStatus.SUCCESS)).isFalse();
    }

    @Test void databaseRejectsNegativeBalanceDuplicateLedgerAndPartiallyCompletedCache() throws Exception {
        String ref=create(55000); BigDecimal total=total();
        confirm(ref,"1000001",55000,id(),200);
        assertThatThrownBy(()->jdbc.update("UPDATE account SET balance=-1 WHERE account_no='1000001'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO ledger_entry(txn_ref,bank_ref,account_no,direction,amount,created_at) SELECT txn_ref,bank_ref,account_no,direction,amount,created_at FROM ledger_entry WHERE direction='DEBIT'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE idempotency SET http_status=NULL"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("ledger_entry")).isEqualTo(2);invariants(total);
    }

    @Test void actualWalletHttpCanRecoverLostCommittedResultAndMerchantStaysWaiting() throws Exception {
        var orderResult=mvc.perform(post("/api/merchant/orders").contentType("application/json")
                .content("{\"amount\":55000,\"description\":\"Ao thun\"}")).andExpect(status().isCreated()).andReturn();
        String order=mapper.readTree(orderResult.getResponse().getContentAsString()).path("orderId").asText();
        var payment=mvc.perform(post("/api/merchant/orders/"+order+"/payments").contentType("application/json").content("{\"expireMinutes\":15}"))
                .andExpect(status().isCreated()).andReturn();
        String ref=mapper.readTree(payment.getResponse().getContentAsString()).path("txnRef").asText();
        var body=mapper.createObjectNode().put("userId","U001").put("txnRef",ref).put("amount",55000).put("pin","123456").put("requestId",id());
        dropResult=true;
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body.toString()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorCode").value("PAYMENT_RESULT_UNKNOWN"));
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(balance("1000001")).isEqualTo(945000);
        dropResult=false;
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(count("ledger_entry")).isEqualTo(2); assertThat(count("idempotency")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id=?",String.class,order)).isEqualTo("WAITING_PAYMENT");
    }

    private String create(long amount) throws Exception {
        var body=mapper.createObjectNode().put("merchantId","MC001").put("orderId","ORD-B5-"+orderNumbers.incrementAndGet())
                .put("amount",amount).put("description","Ao thun").put("expireMinutes",15);
        body.put("signature",hmac.sign(body,"secret-mc001"));
        var result=mvc.perform(post("/api/gateway/payments").contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("txnRef").asText();
    }
    private ObjectNode body(String account,long amount,String key){return mapper.createObjectNode().put("payerAccount",account).put("amount",amount).put("requestId",key);}
    private String sign(String ref,ObjectNode body){return hmac.sign(body.deepCopy().put("txnRef",ref),SECRET);}
    private String path(String ref){return "/api/gateway/payments/"+ref+"/confirm";}
    private Reply raw(String ref,String account,long amount,String key)throws Exception {
        var body=body(account,amount,key);
        var result=mvc.perform(post(path(ref)).header("X-Wallet-Signature",sign(ref,body)).contentType("application/json").content(body.toString())).andReturn();
        var json=mapper.readTree(result.getResponse().getContentAsString());
        assertThat(hmac.verify(json,SECRET,result.getResponse().getHeader("X-Signature"))).isTrue();
        return new Reply(result.getResponse().getStatus(),result.getResponse().getContentAsString());
    }
    private String confirm(String ref,String account,long amount,String key,int expected)throws Exception{
        var reply=raw(ref,account,amount,key);assertThat(reply.status()).isEqualTo(expected);return reply.body();
    }
    private void assertCode(String json,String code)throws Exception {assertThat(mapper.readTree(json).path("errorCode").asText()).isEqualTo(code);}
    private String id(){return UUID.randomUUID().toString();}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private long balance(String number){return jdbc.queryForObject("SELECT balance FROM account WHERE account_no=?",Long.class,number);}
    private String state(String ref){return jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,ref);}
    private BigDecimal total(){return jdbc.queryForObject("SELECT SUM(CAST(balance AS NUMERIC(38,0))) FROM account",BigDecimal.class);}
    private void invariants(BigDecimal expected){
        assertThat(total()).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT bank_ref FROM ledger_entry GROUP BY bank_ref HAVING COUNT(*)<>2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)<>0)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT txn_ref FROM ledger_entry GROUP BY txn_ref HAVING COUNT(DISTINCT bank_ref)>1)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency WHERE response_body IS NULL OR http_status IS NULL",Long.class)).isZero();
    }
    private List<Reply> parallel(List<Callable<Reply>> actions)throws Exception{
        var ready=new CountDownLatch(actions.size());var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(actions.size());
        try{
            var futures=new ArrayList<Future<Reply>>();for(var action:actions)futures.add(executor.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return action.call();}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();var results=new ArrayList<Reply>();
            for(var future:futures)results.add(future.get(25,TimeUnit.SECONDS));return results;
        }finally{start.countDown();executor.shutdownNow();}
    }
    record Reply(int status,String body){}
    private static HttpServer proxy(){
        try{var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(PROXY_THREADS);
            server.createContext("/api/gateway/payments",exchange->{try{
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+exchange.getRequestURI().getPath()))
                        .header("Content-Type","application/json");
                String signature=exchange.getRequestHeaders().getFirst("X-Wallet-Signature");if(signature!=null)request.header("X-Wallet-Signature",signature);
                var reply=HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
                if(dropResult&&exchange.getRequestURI().getPath().endsWith("/confirm"))return;
                exchange.getResponseHeaders().set("X-Signature",reply.headers().firstValue("X-Signature").orElse(""));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(reply.statusCode(),reply.body().length);exchange.getResponseBody().write(reply.body());
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();return server;
        }catch(IOException ex){throw new ExceptionInInitializerError(ex);}
    }
    static class MutableClock extends Clock{
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}
    }
    static class CommittedEvents{
        final List<PaymentCompleted> events=new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT) public void completed(PaymentCompleted event){events.add(event);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestConfig{
        @Bean @Primary MutableClock b5Clock(){return new MutableClock();}
        @Bean CommittedEvents committedEvents(){return new CommittedEvents();}
    }
}

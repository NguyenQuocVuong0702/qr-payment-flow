package vn.vnpay.qr.gateway;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.bank.Account;
import vn.vnpay.qr.gateway.bank.AccountRepository;
import vn.vnpay.qr.gateway.domain.PaymentTxn;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import vn.vnpay.qr.gateway.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:expiry-b7;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(PaymentExpiryIntegrationTest.TestConfig.class)
class PaymentExpiryIntegrationTest {
    static final Instant NOW=Instant.parse("2026-11-13T03:00:00Z");
    static final AtomicInteger numbers=new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired MutableClock clock;
    @Autowired PaymentExpiryJob job;
    @Autowired PaymentExpiryTransaction expiry;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean AccountRepository accounts;
    @MockitoSpyBean PaymentTxnRepository payments;
    ObjectNode creation;
    @BeforeEach void clean() {
        clock.now.set(NOW);jdbc.update("DELETE FROM ledger_entry");jdbc.update("DELETE FROM idempotency");jdbc.update("DELETE FROM payment_txn");jdbc.update("DELETE FROM orders");
        jdbc.update("UPDATE account SET balance=CASE account_no WHEN '1000001' THEN 1000000 WHEN '1000002' THEN 10000 ELSE 0 END");
    }
    @Test void expiresAtTheExactBoundaryAndRepeatedTicksAreNoOpsWithoutFinancialEffects() throws Exception {
        String ref=create(1);String finances=financials();long version=version(ref);
        clock.now.set(NOW.plusSeconds(60).minusNanos(1));assertThat(job.runOnce()).isZero();assertThat(state(ref)).isEqualTo("PENDING");
        clock.now.set(NOW.plusSeconds(60));assertThat(job.runOnce()).isEqualTo(1);assertThat(state(ref)).isEqualTo("EXPIRED");
        assertThat(version(ref)).isEqualTo(version+1);assertThat(job.runOnce()).isZero();assertThat(version(ref)).isEqualTo(version+1);
        assertThat(financials()).isEqualTo(finances);assertThat(count("ipn_outbox")).isZero();assertThat(count("ipn_log")).isZero();
        verify(accounts,never()).findForUpdate(anyString());
    }
    @Test void doesNotTouchSuccessFailedProcessingOrNotYetExpiredPending() throws Exception {
        String success=create(1);assertThat(confirm(success,"1000001",id()).status()).isEqualTo(200);
        String failed=create(1);assertThat(confirm(failed,"1000002",id()).status()).isEqualTo(200);
        String processing=create(1);jdbc.update("UPDATE payment_txn SET status='PROCESSING' WHERE txn_ref=?",processing);
        String future=create(2);String due=create(1);String finances=financials();
        var terminal=jdbc.queryForList("SELECT txn_ref,status,bank_ref,paid_at,version FROM payment_txn WHERE status IN ('SUCCESS','FAILED','PROCESSING') ORDER BY txn_ref");
        clock.now.set(NOW.plusSeconds(60));assertThat(job.runOnce()).isEqualTo(1);assertThat(state(due)).isEqualTo("EXPIRED");assertThat(state(future)).isEqualTo("PENDING");
        assertThat(jdbc.queryForList("SELECT txn_ref,status,bank_ref,paid_at,version FROM payment_txn WHERE status IN ('SUCCESS','FAILED','PROCESSING') ORDER BY txn_ref")).isEqualTo(terminal);
        assertThat(financials()).isEqualTo(finances);assertThat(count("ipn_outbox")).isEqualTo(2);invariants();
    }
    @Test void missingOrFutureTransactionCannotBeExpired() throws Exception {
        assertThat(expiry.expire("TX99999999")).isFalse();String ref=create(1);assertThat(expiry.expire(ref)).isFalse();assertThat(state(ref)).isEqualTo("PENDING");
    }
    @Test void confirmRacingExpiryAfterDeadlineAlwaysRejectsAndCannotMoveMoney() throws Exception {
        String ref=create(1);String key=id();String balances=accountSnapshot();clock.now.set(NOW.plusSeconds(60));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var confirm=pool.submit(()->{start.await();return confirm(ref,"1000001",key);});var expire=pool.submit(()->{start.await();return job.runOnce();});start.countDown();
            var reply=confirm.get(10,TimeUnit.SECONDS);assertThat(reply.status()).isEqualTo(422);assertThat(mapper.readTree(reply.body()).path("errorCode").asText()).isEqualTo("TXN_EXPIRED");
            assertThat(expire.get(10,TimeUnit.SECONDS)).isIn(0,1);assertThat(state(ref)).isEqualTo("EXPIRED");
            assertThat(confirm(ref,"1000001",key)).isEqualTo(reply);assertThat(count("ledger_entry")).isZero();assertThat(count("ipn_outbox")).isZero();
            assertThat(accountSnapshot()).isEqualTo(balances);invariants();
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void confirmationWinningBeforeDeadlineRemainsSuccessfulWhenJobWaitsPastDeadline() throws Exception {
        String ref=create(1);String key=id();clock.now.set(NOW.plusSeconds(59));
        var inBank=new CountDownLatch(1);var releaseBank=new CountDownLatch(1);var scanned=new CountDownLatch(1);
        doAnswer(call->{
            var account=Optional.ofNullable(entityManager.find(Account.class,"1000001",LockModeType.PESSIMISTIC_WRITE));
            inBank.countDown();if(!releaseBank.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");return account;
        }).when(accounts).findForUpdate("1000001");
        doAnswer(call->{var result=jdbc.queryForList("SELECT txn_ref FROM payment_txn WHERE status='PENDING' AND expire_at<=? ORDER BY txn_ref",
                String.class,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));scanned.countDown();return result;}).when(payments).findExpiryCandidates(any());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var confirm=pool.submit(()->confirm(ref,"1000001",key));assertThat(inBank.await(5,TimeUnit.SECONDS)).isTrue();
            clock.now.set(NOW.plusSeconds(60));var expired=pool.submit(()->job.runOnce());assertThat(scanned.await(5,TimeUnit.SECONDS)).isTrue();
            releaseBank.countDown();var result=confirm.get(10,TimeUnit.SECONDS);assertThat(result.status()).isEqualTo(200);
            assertThat(expired.get(10,TimeUnit.SECONDS)).isZero();assertThat(state(ref)).isEqualTo("SUCCESS");
            String bankRef=mapper.readTree(result.body()).path("bankRef").asText();assertThat(bankRef).isNotBlank();
            clock.now.set(NOW.plusSeconds(300));assertThat(job.runOnce()).isZero();assertThat(confirm(ref,"1000001",key)).isEqualTo(result);
            assertThat(count("ledger_entry")).isEqualTo(2);assertThat(count("idempotency")).isEqualTo(1);assertThat(count("ipn_outbox")).isEqualTo(1);invariants();
        } finally {releaseBank.countDown();pool.shutdownNow();}
    }
    @Test void expiryWinningTheRowLockPreventsConfirmAndPreservesMoney() throws Exception {
        String ref=create(1);clock.now.set(NOW.plusSeconds(60));String balances=accountSnapshot();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var startedConfirm=new CountDownLatch(1);
        doAnswer(call->{var result=Optional.ofNullable(entityManager.find(PaymentTxn.class,ref,LockModeType.PESSIMISTIC_WRITE));if(Thread.currentThread().getName().equals("expiry-winner")){
            locked.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");}return result;
        }).when(payments).findForUpdate(ref);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var expired=pool.submit(()->{Thread.currentThread().setName("expiry-winner");return job.runOnce();});assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var confirm=pool.submit(()->{startedConfirm.countDown();return confirm(ref,"1000001",id());});assertThat(startedConfirm.await(5,TimeUnit.SECONDS)).isTrue();
            release.countDown();assertThat(expired.get(10,TimeUnit.SECONDS)).isEqualTo(1);assertThat(confirm.get(10,TimeUnit.SECONDS).status()).isEqualTo(422);
            assertThat(state(ref)).isEqualTo("EXPIRED");assertThat(accountSnapshot()).isEqualTo(balances);assertThat(count("ledger_entry")).isZero();assertThat(count("ipn_outbox")).isZero();invariants();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void clockIsReadAfterAcquiringTheExpiryLock() throws Exception {
        String ref=create(1);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var waiting=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        doAnswer(call->{if(Thread.currentThread().getName().equals("expiry-waiter"))waiting.countDown();
            return Optional.ofNullable(entityManager.find(PaymentTxn.class,ref,LockModeType.PESSIMISTIC_WRITE));}).when(payments).findForUpdate(ref);
        try {
            var owner=pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{payments.findForUpdate(ref);locked.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");}catch(InterruptedException ex){throw new IllegalStateException(ex);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();clock.now.set(NOW.plusSeconds(59));
            var waiter=pool.submit(()->{Thread.currentThread().setName("expiry-waiter");return expiry.expire(ref);});assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();
            clock.now.set(NOW.plusSeconds(60));release.countDown();owner.get(5,TimeUnit.SECONDS);assertThat(waiter.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(state(ref)).isEqualTo("EXPIRED");assertThat(count("ledger_entry")).isZero();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void twoExpiryWorkersUpdateTheSamePaymentOnlyOnce() throws Exception {
        String ref=create(1);long version=version(ref);clock.now.set(NOW.plusSeconds(60));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{start.await();return job.runOnce();});var two=pool.submit(()->{start.await();return job.runOnce();});start.countDown();
            assertThat(one.get(10,TimeUnit.SECONDS)+two.get(10,TimeUnit.SECONDS)).isEqualTo(1);assertThat(version(ref)).isEqualTo(version+1);
            assertThat(state(ref)).isEqualTo("EXPIRED");assertThat(count("ledger_entry")).isZero();assertThat(count("idempotency")).isZero();
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void creationAndConfirmationReplayAfterExpiryCannotCreateAnotherPaymentOrTransfer() throws Exception {
        String ref=create(1);String qr=jdbc.queryForObject("SELECT qr_string FROM payment_txn WHERE txn_ref=?",String.class,ref);String balances=accountSnapshot();
        clock.now.set(NOW.plusSeconds(60));job.runOnce();
        var replay=mapper.readTree(mvc.perform(post("/api/gateway/payments").contentType("application/json").content(creation.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("txnRef").asText()).isEqualTo(ref);assertThat(replay.path("status").asText()).isEqualTo("EXPIRED");assertThat(replay.path("qrString").asText()).isEqualTo(qr);
        String key=id();var first=confirm(ref,"1000001",key);assertThat(first.status()).isEqualTo(422);clock.now.set(NOW.plusSeconds(600));
        assertThat(confirm(ref,"1000001",key)).isEqualTo(first);assertThat(confirm(ref,"1000001",id()).status()).isEqualTo(422);
        assertThat(count("payment_txn")).isEqualTo(1);assertThat(count("idempotency")).isEqualTo(2);assertThat(count("ledger_entry")).isZero();assertThat(count("ipn_outbox")).isZero();
        assertThat(accountSnapshot()).isEqualTo(balances);invariants();
    }
    @Test void databaseFailureAfterExpiryFlushRollsBackAndNextTickCanRetry() throws Exception {
        String ref=create(1);long version=version(ref);clock.now.set(NOW.plusSeconds(60));String finances=financials();
        doAnswer(call->{entityManager.flush();throw new IllegalStateException("Injected expiry write failure");}).when(payments).flush();
        assertThatThrownBy(()->job.runOnce()).isInstanceOf(IllegalStateException.class);assertThat(state(ref)).isEqualTo("PENDING");assertThat(version(ref)).isEqualTo(version);
        assertThat(financials()).isEqualTo(finances);reset(payments);assertThat(job.runOnce()).isEqualTo(1);assertThat(state(ref)).isEqualTo("EXPIRED");
    }

    private String create(int minutes) throws Exception {
        creation=mapper.createObjectNode().put("merchantId","MC001").put("orderId","EXP"+numbers.incrementAndGet())
                .put("amount",55000).put("description","Ao thun").put("expireMinutes",minutes);
        creation.put("signature",hmac.sign(creation,"secret-mc001"));
        return mapper.readTree(mvc.perform(post("/api/gateway/payments").contentType("application/json").content(creation.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("txnRef").asText();
    }
    private Reply confirm(String ref,String account,String id) throws Exception {
        var body=mapper.createObjectNode().put("payerAccount",account).put("amount",55000).put("requestId",id);
        var result=mvc.perform(post("/api/gateway/payments/"+ref+"/confirm").contentType("application/json").content(body.toString())
                .header("X-Wallet-Signature",hmac.sign(body.deepCopy().put("txnRef",ref),"wallet-payment-secret-dev-only"))).andReturn().getResponse();
        return new Reply(result.getStatus(),result.getContentAsString());
    }
    private String state(String ref){return jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,ref);}
    private long version(String ref){return jdbc.queryForObject("SELECT version FROM payment_txn WHERE txn_ref=?",Long.class,ref);}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private String id(){return UUID.randomUUID().toString();}
    private String accountSnapshot(){return jdbc.queryForList("SELECT * FROM account ORDER BY account_no").toString();}
    private String financials(){return accountSnapshot()+"|"+jdbc.queryForList("SELECT * FROM ledger_entry ORDER BY id")
            +jdbc.queryForList("SELECT request_id,txn_ref,amount,http_status,CAST(response_body AS VARCHAR) AS response_body,version FROM idempotency ORDER BY request_id");}
    private void invariants(){
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM account",Long.class)).isEqualTo(1010000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT bank_ref FROM ledger_entry GROUP BY bank_ref HAVING COUNT(*)<>2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)<>0)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT txn_ref FROM ledger_entry GROUP BY txn_ref HAVING COUNT(DISTINCT bank_ref)>1)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency WHERE http_status IS NULL OR response_body IS NULL",Long.class)).isZero();
    }
    record Reply(int status,String body) { }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestConfig {@Bean @Primary MutableClock expiryClock(){return new MutableClock();}}
}

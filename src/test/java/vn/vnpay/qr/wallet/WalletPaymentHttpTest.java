package vn.vnpay.qr.wallet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.wallet.client.WalletConfirmClient;
import vn.vnpay.qr.wallet.repository.WalletUserRepository;
import vn.vnpay.qr.wallet.api.WalletPaymentRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:wallet-b4;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(WalletPaymentHttpTest.TimeConfig.class)
class WalletPaymentHttpTest {
    static final String SECRET = "wallet-payment-secret-dev-only";
    static final Instant NOW = Instant.parse("2026-11-12T02:00:00Z");
    static volatile String mode = "ok";
    static volatile JsonNode received;
    static volatile int calls;
    static final HttpServer SERVER = server();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired WalletUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired BCryptPasswordEncoder bcrypt;
    @MockitoSpyBean WalletConfirmClient client;
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("app.wallet.gateway.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }
    @BeforeEach void resetState() {
        jdbc.update("UPDATE wallet_user SET failed_pin_attempts=0, pin_locked_until=NULL");
        clock.now.set(NOW); mode = "ok"; received = null; calls = 0;
    }
    @AfterAll static void stop() { SERVER.stop(0); }

    @Test void storesOnlyBcryptAndSeedsBothUsers() {
        for (String id : List.of("U001", "U002")) {
            String hash = users.findById(id).orElseThrow().getPinHash();
            assertThat(hash).startsWith("$2").hasSize(60).isNotEqualTo("123456");
            assertThat(bcrypt.matches("123456", hash)).isTrue();
        }
    }
    @Test void correctPinUsesVerifiedAccountAndHttpOutsideTransactionAndKeepsRequestId() throws Exception {
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); })
                .when(client).confirm(any(), anyString());
        String requestId = UUID.randomUUID().toString();
        String body = request("123456", requestId);
        var first = mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS")).andReturn();
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(received.path("payerAccount").asText()).isEqualTo("1000001");
        assertThat(received.path("requestId").asText()).isEqualTo(requestId);
        assertThat(received.has("pin")).isFalse(); assertThat(received.has("userId")).isFalse();
        assertThat(mapper.readTree(first.getResponse().getContentAsString()).path("paidAt").asText()).endsWith("+07:00");
    }
    @Test void thirdWrongPinLocksDurablyAndUnlocksAtExactly15Minutes() throws Exception {
        pay("000000", 401, "INVALID_PIN"); pay("000000", 401, "INVALID_PIN"); pay("000000", 423, "PIN_LOCKED");
        var saved = users.findById("U001").orElseThrow();
        assertThat(saved.getFailedPinAttempts()).isEqualTo(3);
        assertThat(saved.getPinLockedUntil()).isEqualTo(NOW.plusSeconds(900));
        clock.now.set(NOW.plusSeconds(899)); pay("123456", 423, "PIN_LOCKED"); assertThat(calls).isZero();
        clock.now.set(NOW.plusSeconds(900)); pay("123456", 200, null);
        assertThat(users.findById("U001").orElseThrow().getFailedPinAttempts()).isZero();
    }
    @Test void correctPinResetsConsecutiveFailureCounter() throws Exception {
        pay("000000", 401, "INVALID_PIN"); pay("123456", 200, null); pay("000000", 401, "INVALID_PIN");
        assertThat(users.findById("U001").orElseThrow().getFailedPinAttempts()).isEqualTo(1);
    }
    @Test void concurrentWrongPinsDoNotLoseFailureCounts() throws Exception {
        var ready = new CountDownLatch(3); var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i=0;i<3;i++) results.add(executor.submit(() -> {
                ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException();
                return mvc.perform(post("/api/wallet/payments").contentType("application/json")
                        .content(request("000000", UUID.randomUUID().toString()))).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var statuses = new ArrayList<Integer>(); for (var result:results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(401,401,423);
            assertThat(users.findById("U001").orElseThrow().getFailedPinAttempts()).isEqualTo(3);
            assertThat(calls).isZero();
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    @Test void rejectsAccountInjectionAndUnknownUserAndMalformedRequest() throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(request("123456", UUID.randomUUID().toString()));
        body.put("payerAccount", "9000001");
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        body.remove("payerAccount"); body.put("userId", "U404");
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("WALLET_USER_NOT_FOUND"));
        body.put("requestId", "bad");
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        assertThat(calls).isZero();
        assertThat(new WalletPaymentRequest("U001","TX00000001",55000L,"123456",UUID.randomUUID().toString()).toString()).doesNotContain("123456");
    }
    @ParameterizedTest @ValueSource(strings={"drop","500","bad-signature","bad-json","timeout"})
    void networkAndUnauthenticatedResultsRemainUnknownAndRetryUsesSameUuid(String failure) throws Exception {
        mode = failure; String id = UUID.randomUUID().toString();
        var body = request("123456",id);
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorCode").value("PAYMENT_RESULT_UNKNOWN"));
        mode = "ok";
        mvc.perform(post("/api/wallet/payments").contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(received.path("requestId").asText()).isEqualTo(id);
    }
    @Test void authenticatedBusinessErrorIsPropagated() throws Exception { mode="expired"; pay("123456",422,"TXN_EXPIRED"); }
    private String request(String pin,String id) throws Exception {
        return mapper.writeValueAsString(new WalletPaymentRequest("U001","TX00000001",55000L,pin,id));
    }
    private void pay(String pin,int status,String code) throws Exception {
        var result=mvc.perform(post("/api/wallet/payments").contentType("application/json").content(request(pin,UUID.randomUUID().toString())))
                .andExpect(status().is(status)); if(code!=null) result.andExpect(jsonPath("$.errorCode").value(code));
    }
    private static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/gateway/payments/", exchange -> {
                try {
                    calls++; var mapper=new ObjectMapper(); received=mapper.readTree(exchange.getRequestBody());
                    var signed=((com.fasterxml.jackson.databind.node.ObjectNode)received).deepCopy().put("txnRef","TX00000001");
                    if(!new HmacSigner().verify(signed,SECRET,exchange.getRequestHeaders().getFirst("X-Wallet-Signature"))) {
                        exchange.sendResponseHeaders(401,-1); return;
                    }
                    if(mode.equals("drop")) return;
                    if(mode.equals("timeout")) {
                        try { Thread.sleep(6000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    }
                    if(mode.equals("500")) { exchange.sendResponseHeaders(500,-1); return; }
                    var body=mapper.createObjectNode(); int status=200;
                    if(mode.equals("expired")) { status=422; body.put("errorCode","TXN_EXPIRED").put("message","Expired").put("timestamp","2026-11-12T09:00:00+07:00"); }
                    else body.put("txnRef","TX00000001").put("status","SUCCESS").put("amount",55000).put("bankRef","BK20261112000001")
                            .put("paidAt","2026-11-12T09:00:00+07:00").put("message","Thanh toan thanh cong");
                    String signature=mode.equals("bad-signature")?"0".repeat(64):new HmacSigner().sign(body,SECRET);
                    byte[] bytes=(mode.equals("bad-json")?"{":body.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("X-Signature",signature); exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }); server.start(); return server;
        } catch(IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}
        public Instant instant(){return now.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TimeConfig { @Bean @Primary MutableClock paymentTestClock(){return new MutableClock();} }
}

package vn.vnpay.qr.wallet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import vn.vnpay.qr.wallet.client.WalletGatewayClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:wallet-b3;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WalletScanHttpIntegrationTest.TimeConfiguration.class)
class WalletScanHttpIntegrationTest {
    private static final String TOKEN = "wallet-read-token-dev-only";
    private static final Instant NOW = Instant.parse("2026-11-12T02:00:00Z");
    private static final AtomicReference<String> MODE = new AtomicReference<>("forward");
    private static final ExecutorService THREADS = Executors.newCachedThreadPool();
    private static final HttpServer PROXY = proxy();
    private static volatile int applicationPort;
    private static volatile int lookupCalls;
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentTxnRepository payments;
    @Autowired MutableClock clock;
    @MockitoSpyBean WalletGatewayClient gateway;
    private String qr;
    private String txnRef;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.wallet.gateway.base-url", () -> "http://127.0.0.1:" + PROXY.getAddress().getPort());
    }

    @BeforeEach
    void setup() throws Exception {
        applicationPort = port;
        MODE.set("forward");
        lookupCalls = 0;
        clock.now.set(NOW);
        payments.deleteAllInBatch();
        var body = mapper.createObjectNode().put("merchantId", "MC001").put("orderId", "ORD-B3")
                .put("amount", 55000).put("description", "Thanh toan ao thun").put("expireMinutes", 15);
        body.put("signature", hmac.sign(body, "secret-mc001"));
        var result = mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn();
        var payment = mapper.readTree(result.getResponse().getContentAsString());
        qr = payment.path("qrString").asText();
        txnRef = payment.path("txnRef").asText();
    }

    @AfterAll
    static void stop() { PROXY.stop(0); THREADS.shutdownNow(); }

    @Test
    void scansRealB2QrOverHttpWithoutTransactionAndReturnsExactResponse() throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(gateway).get(anyString());
        var result = scan(qr, 200);
        var body = mapper.readTree(result);
        assertThat(body.size()).isEqualTo(7);
        assertThat(body.path("txnRef").asText()).isEqualTo(txnRef);
        assertThat(body.path("merchantName").asText()).isEqualTo("SHOP ABC");
        assertThat(body.path("merchantCity").asText()).isEqualTo("HANOI");
        assertThat(body.path("description").asText()).isEqualTo("Thanh toan ao thun");
        assertThat(body.path("amount").asLong()).isEqualTo(55000);
        assertThat(body.path("currency").asText()).isEqualTo("VND");
        assertThat(body.path("expireAt").asText()).isEqualTo("2026-11-12T09:15:00+07:00");
        assertThat(lookupCalls).isEqualTo(1);
        assertThat(payments.findById(txnRef).orElseThrow().getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void wrongCrcDoesNotCallGateway() throws Exception {
        error(qr.replace("SHOP ABC", "SHOP ABD"), 422, "CRC_MISMATCH");
        assertThat(lookupCalls).isZero();
    }

    @Test
    void missingTagAndMalformedQrDoNotCallGateway() throws Exception {
        error("000201", 422, "INVALID_QR");
        error(qr.replace("52045499", ""), 422, "INVALID_QR");
        assertThat(lookupCalls).isZero();
    }

    @Test
    void changedAmountWithRecalculatedCrcIsRejected() throws Exception {
        error(recrc(qr.replace("540555000", "54045000")), 422, "AMOUNT_MISMATCH");
        assertThat(lookupCalls).isEqualTo(1);
    }

    @Test
    void missingTransactionPropagatesAuthenticated404() throws Exception {
        error(recrc(qr.replace(txnRef, "TX99999999")), 404, "TXN_NOT_FOUND");
    }

    @Test
    void checksExpirationWithClockAtTheExactBoundary() throws Exception {
        clock.now.set(NOW.plusSeconds(899));
        scan(qr, 200);
        clock.now.set(NOW.plusSeconds(900));
        error(qr, 422, "TXN_EXPIRED");
        assertThat(payments.findById(txnRef).orElseThrow().getStatus().name()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "SUCCESS", "FAILED", "REFUNDED"})
    void rejectsEveryNonPendingState(String state) throws Exception {
        jdbc.update("UPDATE payment_txn SET status = ? WHERE txn_ref = ?", state, txnRef);
        error(qr, 422, "TXN_NOT_PAYABLE");
    }

    @Test
    void expiredStateUsesExpiryCode() throws Exception {
        jdbc.update("UPDATE payment_txn SET status = 'EXPIRED' WHERE txn_ref = ?", txnRef);
        error(qr, 422, "TXN_EXPIRED");
    }

    @Test
    void gatewayMetadataAndExpiryOverrideQrMetadata() throws Exception {
        String changed = qr.replace("SHOP ABC", "FAKE ABC").replace("2611120915", "2402290000");
        var body = mapper.readTree(scan(recrc(changed), 200));
        assertThat(body.path("merchantName").asText()).isEqualTo("SHOP ABC");
        jdbc.update("UPDATE payment_txn SET expire_at = ? WHERE txn_ref = ?", java.time.OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC), txnRef);
        clock.now.set(NOW.plusSeconds(2));
        error(qr, 422, "TXN_EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"500", "drop", "bad-json", "wrong-signature", "wrong-reference"})
    void httpFailureOrInvalidResponseCannotProduceSuccessfulScan(String mode) throws Exception {
        MODE.set(mode);
        error(qr, 502, mode.equals("500") || mode.equals("drop") ? "GATEWAY_UNAVAILABLE" : "INVALID_GATEWAY_RESPONSE");
    }

    @Test
    void anonymousOrBadWalletCredentialCannotUseGet() throws Exception {
        mvc.perform(get("/api/gateway/payments/" + txnRef)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gateway/payments/" + txnRef).header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void walletGetHasOnlyConfirmationProjectionAndSignedResponse() throws Exception {
        var result = mvc.perform(get("/api/gateway/payments/" + txnRef).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(8);
        assertThat(body.has("orderId")).isFalse();
        assertThat(body.has("bankRef")).isFalse();
        assertThat(body.has("payerAccount")).isFalse();
        assertThat(hmac.verify(body, TOKEN, result.getResponse().getHeader("X-Signature"))).isTrue();
    }

    @Test
    void merchantGetRequiresHmacAndCannotFallBackToWallet() throws Exception {
        var fields = mapper.createObjectNode().put("merchantId", "MC001").put("txnRef", txnRef);
        String signature = hmac.sign(fields, "secret-mc001");
        var result = mvc.perform(get("/api/gateway/payments/" + txnRef).header("X-Merchant-Id", "MC001")
                        .header("X-Signature", signature)).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("orderId")).isTrue();
        assertThat(body.has("merchantName")).isFalse();
        assertThat(hmac.verify(body, "secret-mc001", result.getResponse().getHeader("X-Signature"))).isTrue();
        mvc.perform(get("/api/gateway/payments/" + txnRef).header("X-Merchant-Id", "MC001"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gateway/payments/" + txnRef).header("X-Merchant-Id", "MC001")
                        .header("X-Signature", "wrong").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
        mvc.perform(get("/api/gateway/payments/" + txnRef).header("X-Signature", signature))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gateway/payments/" + txnRef).header("X-Merchant-Id", "MC001")
                        .header("X-Signature", signature, signature)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gateway/payments/TX99999999").header("X-Merchant-Id", "MC001")
                        .header("X-Signature", signature)).andExpect(status().isUnauthorized());
    }

    @Test
    void validMerchantCannotReadAnotherMerchantsTransaction() throws Exception {
        jdbc.update("INSERT INTO merchant(merchant_id,name,city,mcc,secret_key,ipn_url,account_no) VALUES ('MC002','OTHER','HANOI','5499','other-key','http://localhost/ipn','9000002')");
        try {
            var fields = mapper.createObjectNode().put("merchantId", "MC002").put("txnRef", txnRef);
            mvc.perform(get("/api/gateway/payments/" + txnRef).header("X-Merchant-Id", "MC002")
                            .header("X-Signature", hmac.sign(fields, "other-key")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("TXN_NOT_FOUND"));
        } finally {
            jdbc.update("DELETE FROM merchant WHERE merchant_id = 'MC002'");
        }
    }

    @Test
    void scanInputValidationAndNullQrUseCommonErrorEnvelope() throws Exception {
        mvc.perform(post("/api/wallet/scan").contentType(MediaType.APPLICATION_JSON).content("{\"qrString\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        var body = mapper.createObjectNode().put("userId", "U001");
        mvc.perform(post("/api/wallet/scan").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorCode").value("INVALID_QR"));
        assertThat(lookupCalls).isZero();
    }

    private String scan(String value, int status) throws Exception {
        var body = mapper.createObjectNode().put("userId", "U001").put("qrString", value);
        return mvc.perform(post("/api/wallet/scan").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }

    private void error(String value, int status, String code) throws Exception {
        var body = mapper.readTree(scan(value, status));
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("errorCode").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("timestamp").asText()).endsWith("+07:00");
    }

    private String recrc(String value) { return value.substring(0, value.length() - 4) + vn.vnpay.qr.common.qr.Crc16.calculate(value.substring(0, value.length() - 4)); }

    private static HttpServer proxy() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(THREADS);
            server.createContext("/api/gateway/payments/", exchange -> {
                try {
                    lookupCalls++;
                    if (MODE.get().equals("drop")) return;
                    if (MODE.get().equals("500")) { exchange.sendResponseHeaders(500, -1); return; }
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + exchange.getRequestURI().getPath()))
                            .header("Authorization", exchange.getRequestHeaders().getFirst("Authorization")).GET().build();
                    var reply = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                    String body = reply.body();
                    String signature = reply.headers().firstValue("X-Signature").orElse("");
                    if (MODE.get().equals("bad-json")) body = "{";
                    if (MODE.get().equals("wrong-signature")) signature = "0".repeat(64);
                    if (MODE.get().equals("wrong-reference")) {
                        var mapper = new ObjectMapper();
                        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body);
                        node.put("txnRef", "TX99999999");
                        body = node.toString();
                        signature = new HmacSigner().sign(node, TOKEN);
                    }
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("X-Signature", signature);
                    exchange.sendResponseHeaders(reply.statusCode(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock b3Clock() { return new MutableClock(); }
    }
}

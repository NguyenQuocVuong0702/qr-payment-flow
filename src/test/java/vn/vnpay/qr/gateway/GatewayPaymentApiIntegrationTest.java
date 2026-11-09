package vn.vnpay.qr.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.domain.PaymentStatus;
import vn.vnpay.qr.gateway.qr.Crc16Calculator;
import vn.vnpay.qr.gateway.repository.MerchantRepository;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:gateway-b2;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GatewayPaymentApiIntegrationTest.FixedClock.class)
class GatewayPaymentApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Autowired PaymentTxnRepository payments;
    @Autowired MerchantRepository merchants;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() { payments.deleteAllInBatch(); }

    @Test
    void createsSignedPersistedPendingPaymentWithAllExactQrTags() throws Exception {
        var result = send(request("ORD20261102001", 55000), 201);
        var response = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.size()).isEqualTo(6);
        assertThat(response.path("status").asText()).isEqualTo("PENDING");
        assertThat(response.path("expireAt").asText()).isEqualTo("2026-11-02T09:15:00+07:00");
        assertThat(response.path("txnRef").asText()).matches("TX[0-9]{8}");
        assertThat(hmac.verify(response, "secret-mc001", result.getResponse().getHeader("X-Signature"))).isTrue();
        var saved = payments.findById(response.path("txnRef").asText()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getMerchantId()).isEqualTo("MC001");
        assertThat(saved.getOrderId()).isEqualTo("ORD20261102001");
        assertThat(saved.getAmount()).isEqualTo(55000);
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-11-02T02:00:00Z"));
        assertThat(saved.getExpireAt()).isEqualTo(Instant.parse("2026-11-02T02:15:00Z"));
        String qr = response.path("qrString").asText();
        String expectedWithoutCrc = "00020101021226230010A0000007750105MC0015204549953037045405550005802VN"
                + "5908SHOP ABC6005HANOI62530522012611020915" + saved.getTxnRef()
                + "0818Thanh toan ao thun0901M6304";
        assertThat(qr).isEqualTo(expectedWithoutCrc + Crc16Calculator.calculate(expectedWithoutCrc));
        assertThat(qr).isEqualTo(saved.getQrString());
        assertThat(saved.getBankRef()).isNull();
        assertThat(saved.getPayerAccount()).isNull();
        assertThat(payments.count()).isEqualTo(1);
        // The full CRC fixture was also calculated independently with Python, not a custom parser.
        String fixture = expectedWithoutCrc.replace(saved.getTxnRef(), "TX00000001");
        assertThat(Crc16Calculator.calculate(fixture)).isEqualTo("C9E9");
    }

    @Test
    void startupSeedMatchesExercise() {
        var merchant = merchants.findById("MC001").orElseThrow();
        assertThat(merchant.getName()).isEqualTo("SHOP ABC");
        assertThat(merchant.getCity()).isEqualTo("HANOI");
        assertThat(merchant.getMcc()).isEqualTo("5499");
        assertThat(merchant.getAccountNo()).isEqualTo("9000001");
        assertThat(merchant.getIpnUrl()).isEqualTo("http://localhost:8080/api/merchant/ipn");
    }

    @Test
    void wrongSignatureReturns401AndSignedErrorWithoutInsert() throws Exception {
        var body = request("ORD1", 55000);
        body.put("signature", "0".repeat(64));
        var result = mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"))
                .andReturn();
        assertThat(hmac.verify(mapper.readTree(result.getResponse().getContentAsString()), "secret-mc001",
                result.getResponse().getHeader("X-Signature"))).isTrue();
        assertThat(payments.count()).isZero();
    }

    @Test
    void signedAmountTamperingIsRejected() throws Exception {
        var body = signed(request("ORD1", 55000));
        body.put("amount", 1000);
        mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
        assertThat(payments.count()).isZero();
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(request("ORD1", 55000).toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
    }

    @Test
    void unknownMerchantReturns404() throws Exception {
        var body = request("ORD1", 55000).put("merchantId", "MC404");
        var result = send(body, 404);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
                .isEqualTo("MERCHANT_NOT_FOUND");
        assertThat(payments.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1000,1,A", "500000000,60,AbCdE0123456789 Xyz"})
    void acceptsAmountDescriptionAndExpiryBoundaries(long amount, int minutes, String description) throws Exception {
        var body = request("ORD-BOUNDARY", amount).put("expireMinutes", minutes).put("description", description);
        send(body, 201);
        var txn = payments.findAll().getFirst();
        assertThat(txn.getAmount()).isEqualTo(amount);
        assertThat(txn.getDescription()).isEqualTo(description);
        assertThat(txn.getExpireAt()).isEqualTo(txn.getCreatedAt().plusSeconds(minutes * 60L));
    }

    @Test
    void unknownSignedFieldsAreRejectedRatherThanSilentlyDropped() throws Exception {
        var result = send(request("ORD1", 55000).put("unrecognized", "extra"), 400);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
                .isEqualTo("INVALID_REQUEST");
        assertThat(payments.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void invalidAmountHasCorrectCode(String token) throws Exception {
        ObjectNode body = request("ORD1", 55000);
        body.set("amount", mapper.readTree(token));
        assertError(body, "INVALID_AMOUNT");
    }

    static Stream<String> invalidAmounts() {
        return Stream.of("999", "500000001", "null", "55000.5", "55000.0", "\"55000\"", "9223372036854775808");
    }

    @ParameterizedTest
    @MethodSource("invalidExpiries")
    void invalidExpiryHasCorrectCode(String token) throws Exception {
        var body = request("ORD1", 55000);
        body.set("expireMinutes", mapper.readTree(token));
        assertError(body, "INVALID_EXPIRE_MINUTES");
    }

    static Stream<String> invalidExpiries() {
        return Stream.of("0", "61", "null", "1.5", "\"15\"", "[]", "true");
    }

    @Test
    void invalidDescriptionHasCorrectCode() throws Exception {
        assertError(request("ORD1", 55000).put("description", "A".repeat(20)), "INVALID_DESCRIPTION");
    }

    @Test
    void replayReturnsExactOriginalEvenWhenDescriptionOrExpiryChanges() throws Exception {
        var first = send(request("ORD1", 55000), 201).getResponse().getContentAsString();
        var second = send(request("ORD1", 55000).put("description", "Other").put("expireMinutes", 1), 201)
                .getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void differentAmountConflictsWithoutChangingOriginal() throws Exception {
        send(request("ORD1", 55000), 201);
        var result = send(request("ORD1", 1000), 409);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
                .isEqualTo("ORDER_CONFLICT");
        assertThat(payments.count()).isEqualTo(1);
        assertThat(payments.findAll().getFirst().getAmount()).isEqualTo(55000);
    }

    @Test
    void concurrentCreationHasOnlyOneCommittedTransaction() throws Exception {
        int count = 8;
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                    return send(request("ORD-CONCURRENT", 55000), 201).getResponse().getContentAsString();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var bodies = new HashSet<String>();
            for (var result : results) bodies.add(result.get(20, TimeUnit.SECONDS));
            assertThat(bodies).hasSize(1);
            assertThat(payments.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databaseRejectsDuplicateBusinessKeyDirectly() throws Exception {
        send(request("ORD1", 55000), 201);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_txn(txn_ref, merchant_id, order_id, amount, description, status, qr_string,
                    expire_at, created_at, version)
                SELECT 'TX99999999', merchant_id, order_id, amount, description, status, qr_string,
                    expire_at, created_at, 0 FROM payment_txn
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDifferentAmountsProduceOneCreationAndOneConflict() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (long amount : new long[]{55000L, 1000L}) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                    var body = signed(request("ORD-CONFLICT-RACE", amount));
                    return mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON)
                            .content(body.toString())).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(payments.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertError(ObjectNode body, String code) throws Exception {
        // Invalid JSON field types fail validation before authentication; do not canonicalize containers.
        body.put("signature", "0".repeat(64));
        var result = mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())).andExpect(status().isBadRequest()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText()).isEqualTo(code);
        assertThat(payments.count()).isZero();
    }

    private MvcResult send(ObjectNode body, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(signed(body).toString()))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private ObjectNode request(String orderId, long amount) {
        return mapper.createObjectNode().put("merchantId", "MC001").put("orderId", orderId).put("amount", amount)
                .put("description", "Thanh toan ao thun").put("expireMinutes", 15);
    }

    private ObjectNode signed(ObjectNode body) {
        return body.put("signature", hmac.sign(body, "secret-mc001"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock b2Clock() { return Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneOffset.UTC); }
    }
}

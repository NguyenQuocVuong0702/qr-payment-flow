package vn.vnpay.qr.merchant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import vn.vnpay.qr.merchant.api.CreateOrderRequest;
import vn.vnpay.qr.merchant.domain.OrderStatus;
import vn.vnpay.qr.merchant.repository.OrderRepository;
import vn.vnpay.qr.merchant.service.OrderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderApiIntegrationTest.FixedTime.class)
class OrderApiIntegrationTest {
    private static final String URL = "/api/merchant/orders";
    private static final Instant NOW = Instant.parse("2026-11-01T17:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OrderRepository orders;
    @Autowired OrderService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        orders.deleteAllInBatch();
    }

    @Test
    void createsAndCommitsOrderWithExactlyTheRequiredResponseFields() throws Exception {
        String body = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(""" 
                                {"amount":55000,"description":"Thanh toan ao thun"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value(org.hamcrest.Matchers.matchesPattern("ORD20261102[0-9]{3,}")))
                .andExpect(jsonPath("$.merchantId").value("MC001"))
                .andExpect(jsonPath("$.amount").value(55000))
                .andExpect(jsonPath("$.description").value("Thanh toan ao thun"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-02T00:00:00+07:00"))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = mapper.readTree(body);
        assertThat(response.size()).isEqualTo(6);
        var saved = orders.findById(response.get("orderId").asText()).orElseThrow();
        assertThat(orders.count()).isEqualTo(1);
        assertThat(saved.getMerchantId()).isEqualTo("MC001");
        assertThat(saved.getAmount()).isEqualTo(55000);
        assertThat(saved.getDescription()).isEqualTo("Thanh toan ao thun");
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getTxnRef()).isNull();
        assertThat(saved.getPaidAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("validBoundaries")
    void acceptsInclusiveAmountAndDescriptionBoundaries(long amount, String description) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateOrderRequest(amount, description))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(amount))
                .andExpect(jsonPath("$.description").value(description));
        assertThat(orders.count()).isEqualTo(1);
    }

    static Stream<Arguments> validBoundaries() {
        return Stream.of(Arguments.of(1000L, "A"),
                Arguments.of(500_000_000L, "AbCdE0123456789 Xyz"),
                Arguments.of(55000L, "  Ao thun 123  "));
    }

    @ParameterizedTest(name = "invalid amount JSON token: {0}")
    @MethodSource("invalidAmounts")
    void rejectsInvalidAmountsWithoutWriting(String amountToken) throws Exception {
        expectInvalid("{\"amount\":" + amountToken + ",\"description\":\"Ao thun\"}", "INVALID_AMOUNT");
    }

    static Stream<String> invalidAmounts() {
        return Stream.of("null", "0", "-1", "999", "500000001", "55000.5", "55000.0", "1e3",
                "\"55000\"", "\"\"", "true", "[]", "{}", "9223372036854775808");
    }

    @Test
    void rejectsMissingAmountWithoutWriting() throws Exception {
        expectInvalid("{\"description\":\"Ao thun\"}", "INVALID_AMOUNT");
    }

    @ParameterizedTest(name = "invalid description: {index}")
    @MethodSource("invalidDescriptions")
    void rejectsInvalidDescriptionsWithoutWriting(String description) throws Exception {
        expectInvalid(mapper.writeValueAsString(new CreateOrderRequest(55000L, description)), "INVALID_DESCRIPTION");
    }

    static Stream<String> invalidDescriptions() {
        return Stream.of(null, "", " ", "                   ", "A".repeat(20),
                "Thanh toan don hang ao thun", "Thanh toán", "Ao-thun", "Ao_thun", "Ao&thun", "Ao=thun",
                "Ao\tthun", "Ao\nthun", "Ao thun\n", "Ao\rthun", "Ao\u00a0thun", "Áo", "支付", "Ao 😀");
    }

    @ParameterizedTest
    @MethodSource("wrongDescriptionTypes")
    void rejectsDescriptionCoercion(String token) throws Exception {
        expectInvalid("{\"amount\":55000,\"description\":" + token + "}", "INVALID_DESCRIPTION");
    }

    static Stream<String> wrongDescriptionTypes() {
        return Stream.of("123", "12.5", "true", "[]", "{}");
    }

    @Test
    void rejectsMissingDescription() throws Exception {
        expectInvalid("{\"amount\":55000}", "INVALID_DESCRIPTION");
    }

    @Test
    void reportsAmountFirstWhenBothFieldsAreInvalid() throws Exception {
        expectInvalid("{\"amount\":999,\"description\":\"\"}", "INVALID_AMOUNT");
    }

    @ParameterizedTest
    @MethodSource("malformedRequests")
    void returnsUniformErrorForUnreadableJson(String json) throws Exception {
        expectInvalid(json, "INVALID_REQUEST");
    }

    static Stream<String> malformedRequests() {
        return Stream.of("", "null", "[]", "\"text\"", "{", "{\"amount\":",
                "{\"amount\":55000,\"description\":\"Ao thun\"} {}",
                "{\"amount\":55000,\"amount\":1000,\"description\":\"Ao thun\"}");
    }

    @Test
    void identicalBodiesCreateDistinctOrdersBecauseB1HasNoClientRequestKey() throws Exception {
        var ids = new HashSet<String>();
        for (int i = 0; i < 2; i++) {
            var result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":55000,\"description\":\"Ao thun\"}"))
                    .andExpect(status().isCreated()).andReturn();
            ids.add(mapper.readTree(result.getResponse().getContentAsString()).get("orderId").asText());
        }
        assertThat(ids).hasSize(2);
        assertThat(orders.count()).isEqualTo(2);
    }

    @Test
    void concurrentHttpRequestsCommitDistinctOrderIds() throws Exception {
        int count = 12;
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(count);
        List<Future<String>> responses = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                responses.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start barrier timed out");
                    }
                    var result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"amount\":55000,\"description\":\"Ao thun\"}"))
                            .andExpect(status().isCreated()).andReturn();
                    return mapper.readTree(result.getResponse().getContentAsString()).get("orderId").asText();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var ids = new HashSet<String>();
            for (var response : responses) {
                ids.add(response.get(20, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(count);
            assertThat(orders.count()).isEqualTo(count);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rollsBackOrderWhenEnclosingTransactionFails() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            service.create(new CreateOrderRequest(55000L, "Ao thun"));
            orders.flush(); // The INSERT has reached H2, but is still uncommitted.
            throw new IllegalStateException("Simulated failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(orders.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("databaseViolations")
    void databaseProtectsInvariantsEvenWhenHttpValidationIsBypassed(long amount, String description, String state) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO orders(order_id, merchant_id, amount, description, status, created_at, version)
                VALUES ('ORD-DIRECT', 'MC001', ?, ?, ?, CURRENT_TIMESTAMP, 0)
                """, amount, description, state))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.count()).isZero();
    }

    static Stream<Arguments> databaseViolations() {
        return Stream.of(Arguments.of(999L, "Ao thun", "CREATED"),
                Arguments.of(500_000_001L, "Ao thun", "CREATED"),
                Arguments.of(55000L, "Ao thun rat rat dai 123", "CREATED"),
                Arguments.of(55000L, "Thanh toán", "CREATED"),
                Arguments.of(55000L, "Ao thun\n", "CREATED"),
                Arguments.of(55000L, "Ao\tthun", "CREATED"),
                Arguments.of(55000L, "   ", "CREATED"),
                Arguments.of(55000L, "Ao thun", "SUCCESS"));
    }

    @Test
    void unsupportedMethodStillUsesCommonErrorEnvelope() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-02T00:00:00+07:00"));
    }

    private void expectInvalid(String body, String code) throws Exception {
        var result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value("2026-11-02T00:00:00+07:00"))
                .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(3);
        assertThat(orders.count()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

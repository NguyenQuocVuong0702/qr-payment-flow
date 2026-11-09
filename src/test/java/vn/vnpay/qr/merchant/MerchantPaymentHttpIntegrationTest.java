package vn.vnpay.qr.merchant;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import vn.vnpay.qr.merchant.client.GatewayPaymentClient;
import vn.vnpay.qr.merchant.domain.OrderStatus;
import vn.vnpay.qr.merchant.repository.OrderRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:merchant-b2;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantPaymentHttpIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicReference<String> MODE = new AtomicReference<>("forward");
    private static final ExecutorService PROXY_THREADS = Executors.newCachedThreadPool();
    private static final HttpServer PROXY = createProxy();
    private static volatile int applicationPort;
    private static volatile JsonNode lastRequest;
    private static volatile JsonNode lastResponse;

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentTxnRepository gatewayPayments; // Test-only database assertions across boundaries.
    @MockitoSpyBean OrderRepository orders;
    @MockitoSpyBean GatewayPaymentClient gatewayClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.merchant.gateway.base-url", () -> "http://127.0.0.1:" + PROXY.getAddress().getPort());
    }

    @BeforeEach
    void clean() {
        applicationPort = port;
        MODE.set("forward");
        lastRequest = null;
        lastResponse = null;
        gatewayPayments.deleteAllInBatch();
        orders.deleteAllInBatch();
    }

    @AfterAll
    static void stopProxy() {
        PROXY.stop(0);
        PROXY_THREADS.shutdownNow();
    }

    @Test
    void merchantUsesStoredOrderOverRealHttpWithoutHoldingTransaction() throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(gatewayClient).create(any(), anyInt());
        String orderId = createOrder();
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        var response = start(orderId, 201);
        var body = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(body.path("expireAt").asText()).endsWith("+07:00");
        assertThat(body).isEqualTo(lastResponse);
        var order = orders.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.WAITING_PAYMENT);
        assertThat(order.getTxnRef()).isEqualTo(body.path("txnRef").asText());
        assertThat(lastRequest.path("amount").asLong()).isEqualTo(55000);
        assertThat(lastRequest.path("description").asText()).isEqualTo("Thanh toan ao thun");
        assertThat(lastRequest.path("orderId").asText()).isEqualTo(orderId);
        assertThat(gatewayPayments.count()).isEqualTo(1);
        var replay = start(orderId, 201);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(response.getResponse().getContentAsString());
        assertThat(gatewayPayments.count()).isEqualTo(1);
    }

    @Test
    void lostHttpResultAfterGatewayCommitIsRecoveredByRetryingSameOrder() throws Exception {
        String orderId = createOrder();
        MODE.set("drop-after-commit");
        start(orderId, 502);
        assertThat(gatewayPayments.count()).isEqualTo(1);
        String committedRef = gatewayPayments.findAll().getFirst().getTxnRef();
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        MODE.set("forward");
        var replay = start(orderId, 201);
        assertThat(mapper.readTree(replay.getResponse().getContentAsString()).path("txnRef").asText()).isEqualTo(committedRef);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.WAITING_PAYMENT);
        assertThat(gatewayPayments.count()).isEqualTo(1);
    }

    @Test
    void localSaveFailureRollsBackOrderAndRetryAttachesExistingGatewayPayment() throws Exception {
        String orderId = createOrder();
        doThrow(new DataIntegrityViolationException("Simulated local write failure")).when(orders).flush();
        start(orderId, 500);
        assertThat(gatewayPayments.count()).isEqualTo(1);
        var unchanged = orders.findById(orderId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(unchanged.getTxnRef()).isNull();
        String committedRef = gatewayPayments.findAll().getFirst().getTxnRef();
        reset(orders); // Restore the repository spy's delegate; flush is an interface method.
        start(orderId, 201);
        assertThat(orders.findById(orderId).orElseThrow().getTxnRef()).isEqualTo(committedRef);
        assertThat(gatewayPayments.count()).isEqualTo(1);
    }

    @Test
    void invalidResponseSignatureDoesNotUpdateMerchantAndCanRecover() throws Exception {
        String orderId = createOrder();
        MODE.set("corrupt-signature");
        var result = start(orderId, 502);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
                .isEqualTo("INVALID_GATEWAY_RESPONSE");
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(gatewayPayments.count()).isEqualTo(1);
        MODE.set("forward");
        start(orderId, 201);
        assertThat(gatewayPayments.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed-json", "json-array", "json-null", "nested-json"})
    void malformedGatewayResponseDoesNotUpdateMerchantAndCanRecover(String mode) throws Exception {
        String orderId = createOrder();
        MODE.set(mode);
        var result = start(orderId, 502);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
                .isEqualTo("INVALID_GATEWAY_RESPONSE");
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        MODE.set("forward");
        start(orderId, 201);
        assertThat(gatewayPayments.count()).isEqualTo(1);
    }

    @Test
    void rejectsClientAmountAndDescriptionOverrides() throws Exception {
        String orderId = createOrder();
        mvc.perform(post("/api/merchant/orders/" + orderId + "/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expireMinutes\":15,\"amount\":1000,\"description\":\"Altered\"}"))
                .andExpect(status().isBadRequest());
        assertThat(lastRequest).isNull();
        assertThat(gatewayPayments.count()).isZero();
    }

    @Test
    void rejectsMissingOrderWithoutCallingGateway() throws Exception {
        var result = start("ORD404", 404);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
                .isEqualTo("ORDER_NOT_FOUND");
        assertThat(lastRequest).isNull();
    }

    @Test
    void validatesExpiryAtMerchantBeforeHttp() throws Exception {
        String orderId = createOrder();
        mvc.perform(post("/api/merchant/orders/" + orderId + "/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expireMinutes\":61}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_EXPIRE_MINUTES"));
        assertThat(lastRequest).isNull();
    }

    @Test
    void concurrentMerchantRequestsLinkTheSameTransaction() throws Exception {
        String orderId = createOrder();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> action = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                return start(orderId, 201).getResponse().getContentAsString();
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
            assertThat(gatewayPayments.count()).isEqualTo(1);
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.WAITING_PAYMENT);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String createOrder() throws Exception {
        var result = mvc.perform(post("/api/merchant/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":55000,\"description\":\"Thanh toan ao thun\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("orderId").asText();
    }

    private MvcResult start(String orderId, int status) throws Exception {
        return mvc.perform(post("/api/merchant/orders/" + orderId + "/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expireMinutes\":15}"))
                .andExpect(status().is(status)).andReturn();
    }

    private static HttpServer createProxy() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(PROXY_THREADS);
            server.createContext("/api/gateway/payments", exchange -> {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    lastRequest = new ObjectMapper().readTree(body);
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/gateway/payments"))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var reply = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    lastResponse = new ObjectMapper().readTree(reply.body());
                    if (MODE.get().equals("drop-after-commit")) return; // Drop only after real Gateway has committed.
                    String signature = MODE.get().equals("corrupt-signature") ? "0".repeat(64)
                            : reply.headers().firstValue("X-Signature").orElse("");
                    exchange.getResponseHeaders().set("X-Signature", signature);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    byte[] responseBody = switch (MODE.get()) {
                        case "malformed-json" -> "{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        case "json-array" -> "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        case "json-null" -> "null".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        case "nested-json" -> "{\"unexpected\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        default -> reply.body();
                    };
                    exchange.sendResponseHeaders(reply.statusCode(), responseBody.length);
                    exchange.getResponseBody().write(responseBody);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}

package vn.vnpay.qr.gateway;

import java.io.File;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Three production JVMs, a real callback and persistent H2; no Spring test context in children. */
class IpnRestartIT {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Process> processes = new ArrayList<>();
    private volatile String base;

    @Test
    void readyJobSurvivesCrashThenLostAckIsRecoveredAfterAnotherRestart() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "restart-b6-").toAbsolutePath();
        String url = "jdbc:h2:file:" + directory.resolve("payments").toString().replace('\\', '/');
        var firstProcessed = new CountDownLatch(1);
        var releaseLostAck = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var replies = new CopyOnWriteArrayList<String>();
        var callbackFailure = new AtomicReference<Throwable>();
        var executor = Executors.newCachedThreadPool();
        var callback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callback.setExecutor(executor);
        callback.createContext("/ipn", exchange -> {
            try {
                int attempt = calls.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/merchant/ipn"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                replies.add(mapper.readTree(response.body()).path("code").asText());
                if (attempt == 1) {
                    firstProcessed.countDown(); // Merchant has committed PAID; Gateway has not received the ACK.
                    releaseLostAck.await(20, TimeUnit.SECONDS);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.body().length);
                exchange.getResponseBody().write(response.body());
                exchange.getResponseBody().close();
                delivered.countDown();
            } catch (Throwable ex) { callbackFailure.compareAndSet(null, ex); }
            finally { exchange.close(); }
        });
        callback.start();
        Path seed = directory.resolve("seed.sql");
        Files.writeString(seed, Files.readString(Path.of("src/main/resources/data.sql"), StandardCharsets.UTF_8)
                + "\nUPDATE merchant SET ipn_url='http://127.0.0.1:" + callback.getAddress().getPort()
                + "/ipn' WHERE merchant_id='MC001';\n", StandardCharsets.UTF_8);
        try {
            Process first = start(url, seed, directory.resolve("first.log"), false);
            JsonNode order = mapper.readTree(post("/api/merchant/orders", mapper.createObjectNode()
                    .put("amount", 55000).put("description", "Ao thun"), 201));
            JsonNode payment = mapper.readTree(post("/api/merchant/orders/" + order.path("orderId").asText()
                    + "/payments", mapper.createObjectNode().put("expireMinutes", 60), 201));
            ObjectNode request = mapper.createObjectNode().put("userId", "U001")
                    .put("txnRef", payment.path("txnRef").asText()).put("amount", 55000).put("pin", "123456")
                    .put("requestId", UUID.randomUUID().toString());
            String result = post("/api/wallet/payments", request, 200);
            assertThat(mapper.readTree(result).path("status").asText()).isEqualTo("SUCCESS");
            String bankRef = mapper.readTree(result).path("bankRef").asText();
            kill(first); // Crash immediately after committed B5; no worker has claimed the outbox.
            assertThat(calls.get()).isZero();
            try (var db = DriverManager.getConnection(url, "sa", ""); var sql = db.createStatement()) {
                scalar(sql, "SELECT COUNT(*) FROM ipn_outbox WHERE state='READY' AND attempt_count=0", 1);
                scalar(sql, "SELECT COUNT(*) FROM ipn_log", 0);
                scalar(sql, "SELECT COUNT(*) FROM orders WHERE status='WAITING_PAYMENT'", 1);
            }

            Process second = start(url, seed, directory.resolve("second.log"), true);
            assertThat(firstProcessed.await(15, TimeUnit.SECONDS)).isTrue();
            kill(second); // Kill while the real HTTP request is awaiting its ACK.
            releaseLostAck.countDown();
            try (var db = DriverManager.getConnection(url, "sa", ""); var sql = db.createStatement()) {
                scalar(sql, "SELECT COUNT(*) FROM orders WHERE status='PAID'", 1);
                scalar(sql, "SELECT COUNT(*) FROM ipn_outbox WHERE state='LEASED' AND attempt_count=1", 1);
                scalar(sql, "SELECT COUNT(*) FROM ipn_log WHERE response_code='IN_PROGRESS'", 1);
                // Advance only the lease fixture while no application owns this H2 file. No real 60s wait.
                sql.executeUpdate("UPDATE ipn_outbox SET lease_until=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00Z'");
            }

            Process third = start(url, seed, directory.resolve("third.log"), true);
            assertThat(delivered.await(15, TimeUnit.SECONDS)).isTrue();
            // Exercise the original Wallet retry while the recovered worker completes its separate short transaction.
            for (int i = 0; i < 5; i++) assertThat(post("/api/wallet/payments", request, 200)).isEqualTo(result);
            kill(third);
            assertThat(callbackFailure.get()).isNull();
            assertThat(replies).containsExactly("00", "02");
            assertThat(calls.get()).isEqualTo(2);
            try (var db = DriverManager.getConnection(url, "sa", ""); var sql = db.createStatement()) {
                scalar(sql, "SELECT COUNT(*) FROM ipn_outbox WHERE state='DELIVERED' AND attempt_count=2", 1);
                scalar(sql, "SELECT COUNT(*) FROM ipn_log", 2);
                scalar(sql, "SELECT COUNT(*) FROM ipn_log WHERE response_code='UNKNOWN_AFTER_CRASH'", 1);
                scalar(sql, "SELECT COUNT(*) FROM ipn_log WHERE attempt=2 AND response_code='02'", 1);
                scalar(sql, "SELECT COUNT(*) FROM orders WHERE status='PAID' AND paid_at IS NOT NULL", 1);
                scalar(sql, "SELECT COUNT(*) FROM idempotency", 1);
                scalar(sql, "SELECT COUNT(*) FROM ledger_entry", 2);
                scalar(sql, "SELECT balance FROM account WHERE account_no='1000001'", 945000);
                scalar(sql, "SELECT balance FROM account WHERE account_no='9000001'", 55000);
                scalar(sql, "SELECT SUM(balance) FROM account", 1010000);
                try (var rows = sql.executeQuery("SELECT bank_ref FROM payment_txn")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(bankRef);
                }
            }
        } finally {
            releaseLostAck.countDown();
            for (Process process : processes) if (process.isAlive()) kill(process);
            callback.stop(0); executor.shutdownNow();
        }
    }

    private Process start(String url, Path seed, Path log, boolean enabled) throws Exception {
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        String cp = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.replace('\\', '/').endsWith("/test-classes")).collect(Collectors.joining(File.pathSeparator));
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var builder = new ProcessBuilder(java.toString(), "-cp", cp, "vn.vnpay.qr.QrPaymentApplication",
                "--server.port=" + port, "--spring.datasource.url=" + url + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.password=", "--spring.sql.init.data-locations=" + seed.toUri(),
                "--app.gateway.ipn.enabled=" + enabled, "--app.gateway.ipn.poll-millis=100",
                "--app.gateway.ipn.second-delay=PT0S", "--app.gateway.ipn.third-delay=PT0S")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("QR_GATEWAY_BASE_URL"); builder.environment().remove("QR_DB_PASSWORD");
        var process = builder.start(); processes.add(process);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("Application startup log: %s", log).isTrue();
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/merchant/orders"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 405) return process;
            } catch (java.io.IOException ex) { /* Wait for the real HTTP port. */ }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Startup timeout; see " + log);
    }
    private String post(String path, JsonNode body, int expected) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s: %s", path, response.body()).isEqualTo(expected);
        return response.body();
    }
    private void scalar(java.sql.Statement sql, String query, long expected) throws Exception {
        try (var rows = sql.executeQuery(query)) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(expected); }
    }
    private void kill(Process process) throws Exception {
        process.destroyForcibly(); assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
    }
}

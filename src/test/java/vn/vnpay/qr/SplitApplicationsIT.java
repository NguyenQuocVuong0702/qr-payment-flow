package vn.vnpay.qr;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.vnpay.qr.common.crypto.HmacSigner;
import static org.assertj.core.api.Assertions.assertThat;

class SplitApplicationsIT {
    private static final String SECRET = "secret-mc001";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HmacSigner hmac = new HmacSigner();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Process> processes = new ArrayList<>();

    @AfterEach void stopProcesses() throws InterruptedException {
        for (var process : processes) stop(process);
    }

    @Test void threeProcessesCompletePaymentRefundAndReconciliationWithSeparateDatabases() throws Exception {
        var dir = Files.createTempDirectory(Path.of("target"), "split-apps-").toAbsolutePath();
        int[] ports = reservePorts();
        var merchant = start(dir, "merchant", ports[0], "--app.merchant.gateway.base-url=" + base(ports[1]),
                "--app.merchant.gateway.secret-key=" + SECRET);
        var gateway = start(dir, "gateway", ports[1], "--app.gateway.demo-ipn-url=" + base(ports[0]) + "/api/merchant/ipn",
                "--app.merchant.gateway.secret-key=" + SECRET, "--app.gateway.confirm.secret=split-payment-secret",
                "--app.gateway.wallet.read-token=split-read-token", "--app.gateway.ipn.poll-millis=200");
        var wallet = start(dir, "wallet", ports[2], "--app.wallet.gateway.base-url=" + base(ports[1]),
                "--app.wallet.gateway.read-token=split-read-token", "--app.wallet.payment.secret=split-payment-secret");
        awaitHttp(merchant);
        awaitHttp(gateway);
        awaitHttp(wallet);

        var order = postJson(merchant, "/api/merchant/orders", mapper.createObjectNode().put("amount", 55_000)
                .put("description", "Ao thun"), 201);
        String orderPath = "/api/merchant/orders/" + order.path("orderId").asText();
        var payment = postJson(merchant, orderPath + "/payments", mapper.createObjectNode().put("expireMinutes", 15), 201);
        String ref = payment.path("txnRef").asText();
        var scan = postJson(wallet, "/api/wallet/scan", mapper.createObjectNode().put("userId", "U001")
                .put("qrString", payment.path("qrString").asText()), 200);
        assertThat(scan.path("amount").asLong()).isEqualTo(55_000);
        var receipt = postJson(wallet, "/api/wallet/payments", mapper.createObjectNode().put("txnRef", ref)
                .put("userId", "U001").put("amount", 55_000).put("pin", "123456")
                .put("requestId", UUID.randomUUID().toString()), 200);
        assertThat(receipt.path("status").asText()).isEqualTo("SUCCESS");
        awaitPaid(merchant, orderPath);

        // An endpoint from a different application is not mapped on this port.
        assertThat(send(get(merchant, "/api/gateway/payments/" + ref)).statusCode()).isEqualTo(404);
        assertThat(send(get(gateway, orderPath)).statusCode()).isEqualTo(404);
        assertThat(send(get(wallet, orderPath)).statusCode()).isEqualTo(404);

        var refundBody = mapper.createObjectNode().put("amount", 55_000).put("requestId", UUID.randomUUID().toString());
        var refund = signed(jsonPost(gateway, "/api/gateway/payments/" + ref + "/refund", refundBody),
                refundBody.deepCopy().put("action", "refund").put("txnRef", ref));
        assertThat(json(send(refund), 200).path("status").asText()).isEqualTo("REFUNDED");

        String date = OffsetDateTime.parse(receipt.path("paidAt").asText()).toLocalDate().toString();
        var report = postJson(merchant, "/api/merchant/reconcile", mapper.createObjectNode().put("date", date), 200);
        assertThat(report.path("matched").asInt()).isOne();
        assertThat(report.path("gatewayTotal").asLong()).isEqualTo(55_000);
        var download = signed(get(gateway, "/api/gateway/reconciliation/csv?merchantId=MC001&date=" + date),
                mapper.createObjectNode().put("date", date));
        var csv = send(download);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/csv");
        stop(gateway.process());
        var upload = request(merchant, "/api/merchant/reconcile/csv?date=" + date).header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofByteArray(csv.body()));
        assertThat(json(send(upload), 200)).isEqualTo(report);
        stop(merchant.process());
        stop(wallet.process());

        assertThat(tables(merchant)).containsExactly("ORDERS");
        assertThat(tables(wallet)).containsExactly("WALLET_USER");
        assertThat(tables(gateway)).containsExactlyInAnyOrder("ACCOUNT", "MERCHANT", "PAYMENT_TXN", "LEDGER_ENTRY",
                "IDEMPOTENCY", "IPN_OUTBOX", "IPN_LOG", "REFUND_TXN", "REFUND_LEDGER_ENTRY");
        try (var connection = DriverManager.getConnection(gateway.database(), "sa", ""); var sql = connection.createStatement();
                var row = sql.executeQuery("SELECT balance FROM account WHERE account_no = '1000001'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(1_000_000);
        }
    }

    private record Instance(Process process, String base, String database, Path log) { }

    private Instance start(Path dir, String mode, int port, String... settings) throws IOException {
        String database = "jdbc:h2:file:" + dir.resolve(mode).toString().replace('\\', '/');
        Path log = dir.resolve(mode + ".log");
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.replace('\\', '/').endsWith("/test-classes"))
                .collect(Collectors.joining(File.pathSeparator));
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(java.toString(), "-Xmx256m", "-cp", classpath, QrPaymentApplication.class.getName(),
                "--app.mode=" + mode, "--server.port=" + port, "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.datasource.url=" + database + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE"));
        command.addAll(List.of(settings));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(process);
        return new Instance(process, base(port), database, log);
    }

    private void awaitHttp(Instance instance) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            assertThat(instance.process().isAlive()).as("Startup log: %s", instance.log()).isTrue();
            try {
                if (send(get(instance, "/")).statusCode() == 404 && Files.readString(instance.log()).contains("Started QrPaymentApplication")) return;
            } catch (IOException ex) { /* Port is still binding. */ }
            Thread.sleep(150);
        }
        throw new AssertionError("Application startup timed out: " + instance.log());
    }

    private void awaitPaid(Instance merchant, String path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if ("PAID".equals(json(send(get(merchant, path)), 200).path("status").asText())) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Merchant did not receive Gateway IPN");
    }

    private JsonNode postJson(Instance instance, String path, JsonNode body, int status) throws Exception {
        return json(send(jsonPost(instance, path, body)), status);
    }

    private HttpRequest.Builder jsonPost(Instance instance, String path, JsonNode body) {
        return request(instance, path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    private HttpRequest.Builder signed(HttpRequest.Builder request, ObjectNode fields) {
        fields.put("merchantId", "MC001");
        return request.header("X-Merchant-Id", "MC001").header("X-Signature", hmac.sign(fields, SECRET));
    }

    private HttpRequest.Builder get(Instance instance, String path) { return request(instance, path).GET(); }

    private HttpRequest.Builder request(Instance instance, String path) {
        return HttpRequest.newBuilder(URI.create(instance.base() + path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode json(HttpResponse<byte[]> response, int expected) throws IOException {
        assertThat(response.statusCode()).as("%s: %s", response.uri(), new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(expected);
        return mapper.readTree(response.body());
    }

    private List<String> tables(Instance instance) throws Exception {
        try (var connection = DriverManager.getConnection(instance.database(), "sa", ""); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'")) {
            var names = new ArrayList<String>();
            while (rows.next()) names.add(rows.getString(1));
            return names;
        }
    }

    private static int[] reservePorts() throws IOException {
        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        try (var one = new ServerSocket(0, 1, localhost); var two = new ServerSocket(0, 1, localhost);
                var three = new ServerSocket(0, 1, localhost)) {
            return new int[] {one.getLocalPort(), two.getLocalPort(), three.getLocalPort()};
        }
    }

    private static String base(int port) { return "http://127.0.0.1:" + port; }

    private static void stop(Process process) throws InterruptedException {
        if (!process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}

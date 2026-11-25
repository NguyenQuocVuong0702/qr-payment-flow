package vn.vnpay.qr.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vn.vnpay.qr.common.crypto.HmacSigner;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checks a Gateway-generated QR against the original parser over HTTP. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:parser-compatibility;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QrParserCompatibilityTest {
    private static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;

    @Test
    void originalQrParserAcceptsActualGatewayQrAsType04() throws Exception {
        var body = mapper.createObjectNode().put("merchantId", "MC001").put("orderId", "ORD-PARSER-04")
                .put("amount", 55000).put("description", "Thanh toan ao thun").put("expireMinutes", 15);
        body.put("signature", hmac.sign(body, "secret-mc001"));
        var result = mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn();
        String qr = mapper.readTree(result.getResponse().getContentAsString()).path("qrString").asText();

        String parserUrl = System.getProperty("qr.parser.url");
        if (parserUrl != null && !parserUrl.isBlank()) {
            assertAcceptedBy(parserUrl.strip(), qr);
            return;
        }
        Path jar = originalParserJar();
        Path log = Files.createTempFile(Files.createDirectories(Path.of("target")), "qr-parser-", ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process parser = new ProcessBuilder(java.toString(), "-jar", jar.toAbsolutePath().toString(),
                "--server.address=127.0.0.1", "--server.port=0")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertAcceptedBy(awaitParser(parser, log), qr);
        } finally {
            parser.destroy();
            if (!parser.waitFor(5, TimeUnit.SECONDS)) {
                parser.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Path originalParserJar() {
        String configured = System.getProperty("qr.parser.jar");
        if (configured != null && !configured.isBlank()) {
            Path jar = Path.of(configured);
            assertThat(jar).as("Original qr-parser JAR from -Dqr.parser.jar").isRegularFile();
            return jar;
        }
        String artifact = "target/qr-parser-0.0.1-SNAPSHOT.jar";
        return List.of(Path.of("../qr-parser", artifact), Path.of("../qr-parser/qr-parser", artifact)).stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Build the original qr-parser once in the sibling directory, "
                        + "or set -Dqr.parser.jar=<path> or -Dqr.parser.url=<running parser URL>. See README."));
    }

    private static String awaitParser(Process parser, Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (parser.isAlive() && System.nanoTime() < deadline) {
            var port = TOMCAT_PORT.matcher(Files.readString(log));
            if (port.find()) {
                return "http://127.0.0.1:" + port.group(1);
            }
            parser.waitFor(200, TimeUnit.MILLISECONDS);
        }
        throw new AssertionError("Original qr-parser did not start. See " + log.toAbsolutePath());
    }

    private void assertAcceptedBy(String parserUrl, String qr) throws Exception {
        var parserRequest = mapper.createObjectNode().put("qr", qr).put("type", "04");
        var request = HttpRequest.newBuilder(URI.create(parserUrl.replaceFirst("/+$", "") + "/api/qr/analyze"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(parserRequest.toString())).build();
        var reply = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).isEqualTo(200);
        var analyzed = mapper.readTree(reply.body());
        assertThat(analyzed.path("status").asText()).isEqualTo("VALID");
        assertThat(analyzed.path("errors").size()).isZero();
        assertThat(analyzed.path("crcResult").path("status").asText()).isEqualTo("VALID");
    }
}

package vn.vnpay.qr.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** Explicit external-project check: mvnw -Dtest=QrParserCompatibilityIT -Dqr.parser.url=http://localhost:8081 test. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:parser-compatibility;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QrParserCompatibilityIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;

    @Test
    void originalQrParserAcceptsActualGatewayQrAsType04() throws Exception {
        String parserUrl = System.getProperty("qr.parser.url");
        assertThat(parserUrl).as("Start the original qr-parser and supply -Dqr.parser.url; this test never substitutes a parser")
                .isNotBlank();
        var body = mapper.createObjectNode().put("merchantId", "MC001").put("orderId", "ORD-PARSER-04")
                .put("amount", 55000).put("description", "Thanh toan ao thun").put("expireMinutes", 15);
        body.put("signature", hmac.sign(body, "secret-mc001"));
        var result = mvc.perform(post("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn();
        String qr = mapper.readTree(result.getResponse().getContentAsString()).path("qrString").asText();
        var parserRequest = mapper.createObjectNode().put("qr", qr).put("type", "04");
        var request = HttpRequest.newBuilder(URI.create(parserUrl + "/api/qr/analyze"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(parserRequest.toString())).build();
        var reply = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).isEqualTo(200);
        var analyzed = mapper.readTree(reply.body());
        assertThat(analyzed.path("status").asText()).isEqualTo("VALID");
        assertThat(analyzed.path("errors").size()).isZero();
        assertThat(analyzed.path("crcResult").path("status").asText()).isEqualTo("VALID");
    }
}

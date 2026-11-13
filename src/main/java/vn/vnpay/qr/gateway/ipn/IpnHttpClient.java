package vn.vnpay.qr.gateway.ipn;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class IpnHttpClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    public IpnHttpClient(RestClient.Builder builder, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        client = builder.clone().requestFactory(factory).build(); this.mapper = mapper;
    }
    public IpnDeliveryResult send(IpnClaim claim) {
        try {
            return client.post().uri(claim.url()).contentType(MediaType.APPLICATION_JSON).body(claim.requestBody())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) return new IpnDeliveryResult(false, "HTTP_" + status, status, null);
                        try {
                            byte[] bytes = response.getBody().readNBytes(4097);
                            if (bytes.length > 4096) return invalid(status);
                            var body = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
                            if (body == null || !body.isObject() || !body.path("code").isTextual()
                                    || !body.path("code").asText().matches("[0-9]{2}")) return invalid(status);
                            String code = body.path("code").asText();
                            return new IpnDeliveryResult(code.equals("00") || code.equals("02"), code, status, null);
                        } catch (SocketTimeoutException ex) { return new IpnDeliveryResult(false, "TIMEOUT", status, "SocketTimeoutException"); }
                        catch (IOException ex) { return invalid(status); }
                    });
        } catch (RestClientException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            return new IpnDeliveryResult(false, cause instanceof SocketTimeoutException ? "TIMEOUT" : "IO_ERROR", null,
                    cause.getClass().getSimpleName());
        } catch (IllegalArgumentException ex) { return new IpnDeliveryResult(false, "INVALID_URL", null, "IllegalArgumentException"); }
    }
    private IpnDeliveryResult invalid(int status) { return new IpnDeliveryResult(false, "INVALID_RESPONSE", status, null); }
}

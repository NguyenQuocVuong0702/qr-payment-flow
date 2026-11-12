package vn.vnpay.qr.wallet.client;

import java.io.IOException;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.wallet.config.WalletGatewayProperties;

@Component
public class WalletGatewayClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;
    private final WalletGatewayProperties config;

    public WalletGatewayClient(RestClient.Builder builder, ObjectMapper mapper, HmacSigner hmac, WalletGatewayProperties config) {
        this.client = builder.clone().baseUrl(config.baseUrl()).build();
        this.mapper = mapper;
        this.hmac = hmac;
        this.config = config;
    }

    public GatewayPaymentDetails get(String txnRef) {
        try {
            var result = client.get().uri("/api/gateway/payments/{txnRef}", txnRef)
                    .headers(headers -> headers.setBearerAuth(config.readToken())).exchange((request, response) -> {
                        if (response.getStatusCode().is5xxServerError()) throw unavailable();
                        try {
                            JsonNode json = mapper.readTree(response.getBody());
                            if (json == null || !json.isObject()
                                    || !hmac.verify(json, config.readToken(), response.getHeaders().getFirst("X-Signature"))) {
                                throw invalidResponse();
                            }
                            if (response.getStatusCode().value() == 404 && "TXN_NOT_FOUND".equals(json.path("errorCode").asText())) {
                                throw new ApiException(HttpStatus.NOT_FOUND, "TXN_NOT_FOUND", "Payment transaction not found");
                            }
                            if (response.getStatusCode().value() != 200) throw invalidResponse();
                            return mapper.treeToValue(json, GatewayPaymentDetails.class);
                        } catch (IOException | IllegalArgumentException ex) {
                            throw invalidResponse();
                        }
                    });
            if (result == null || !txnRef.equals(result.txnRef()) || result.amount() == null
                    || result.amount() < 1000 || result.amount() > 500_000_000
                    || result.merchantName() == null || result.merchantName().isBlank()
                    || result.merchantCity() == null || result.merchantCity().isBlank()
                    || result.description() == null || result.description().isBlank() || !"VND".equals(result.currency())
                    || result.status() == null || !Set.of("PENDING", "PROCESSING", "SUCCESS", "FAILED", "EXPIRED", "REFUNDED").contains(result.status())
                    || result.expireAt() == null) throw invalidResponse();
            return result;
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE", "Gateway lookup is unavailable");
    }

    private static ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_GATEWAY_RESPONSE", "Gateway lookup response is invalid or unauthenticated");
    }
}

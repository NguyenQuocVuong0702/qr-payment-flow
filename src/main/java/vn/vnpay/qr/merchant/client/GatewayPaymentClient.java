package vn.vnpay.qr.merchant.client;

import java.util.Map;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.merchant.config.MerchantGatewayProperties;
import vn.vnpay.qr.merchant.service.OrderPaymentSnapshot;

@Component
public class GatewayPaymentClient {
    private final RestClient client;
    private final MerchantGatewayProperties properties;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;

    public GatewayPaymentClient(RestClient.Builder builder, MerchantGatewayProperties properties,
            ObjectMapper mapper, HmacSigner hmac) {
        this.client = builder.clone().baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.mapper = mapper;
        this.hmac = hmac;
    }

    public GatewayPayment create(OrderPaymentSnapshot order, int expireMinutes) {
        var body = mapper.createObjectNode();
        body.put("merchantId", order.merchantId()).put("orderId", order.orderId()).put("amount", order.amount())
                .put("description", order.description()).put("expireMinutes", expireMinutes);
        body.put("signature", hmac.sign(body, properties.secretKey()));
        try {
            var response = client.post().uri("/api/gateway/payments").contentType(MediaType.APPLICATION_JSON)
                    .body(body).exchange((request, reply) -> {
                        JsonNode json;
                        try {
                            json = mapper.readTree(reply.getBody());
                        } catch (IOException ex) {
                            throw invalidResponse();
                        }
                        if (json == null || !json.isObject()) {
                            throw invalidResponse();
                        }
                        try {
                            if (!hmac.verify(json, properties.secretKey(), reply.getHeaders().getFirst("X-Signature"))) {
                                throw invalidResponse();
                            }
                        } catch (IllegalArgumentException ex) {
                            throw invalidResponse();
                        }
                        if (reply.getStatusCode().value() != 201) {
                            // Forward only authenticated, recognized protocol errors; no remote message reflection.
                            String code = json.path("errorCode").asText();
                            var allowed = Map.of("INVALID_SIGNATURE", HttpStatus.UNAUTHORIZED,
                                    "ORDER_CONFLICT", HttpStatus.CONFLICT,
                                    "INVALID_AMOUNT", HttpStatus.BAD_REQUEST,
                                    "INVALID_DESCRIPTION", HttpStatus.BAD_REQUEST,
                                    "INVALID_EXPIRE_MINUTES", HttpStatus.BAD_REQUEST);
                            if (allowed.containsKey(code) && allowed.get(code).value() == reply.getStatusCode().value()) {
                                throw new ApiException(allowed.get(code), code, "Gateway rejected payment request");
                            }
                            throw invalidResponse();
                        }
                        try {
                            return mapper.treeToValue(json, GatewayPayment.class);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                            throw invalidResponse();
                        }
                    });
            if (response == null || !order.orderId().equals(response.orderId()) || order.amount() != response.amount()
                    || !"PENDING".equals(response.status()) || response.txnRef() == null
                    || !response.txnRef().matches("TX[0-9]{8}") || response.expireAt() == null
                    || response.qrString() == null || response.qrString().isBlank()) {
                throw invalidResponse();
            }
            return response;
        } catch (RestClientException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE",
                    "Gateway result is unknown; retry payment creation with the same orderId");
        }
    }

    private static ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_GATEWAY_RESPONSE",
                "Gateway response could not be authenticated or does not match the order");
    }
}

package vn.vnpay.qr.merchant.client;

import java.util.Map;
import java.util.Set;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.config.BoundedHttpRequests;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.merchant.config.MerchantGatewayProperties;
import vn.vnpay.qr.merchant.service.OrderPaymentSnapshot;
import vn.vnpay.qr.merchant.api.OrderDetails;
import vn.vnpay.qr.common.time.BusinessDay;

@Component
public class GatewayPaymentClient {
    private final RestClient client;
    private final RestClient lookupClient;
    private final MerchantGatewayProperties properties;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;

    public GatewayPaymentClient(RestClient.Builder builder, MerchantGatewayProperties properties,
            ObjectMapper mapper, HmacSigner hmac) {
        this.client = builder.clone().baseUrl(properties.baseUrl()).build();
        this.lookupClient = builder.clone().baseUrl(properties.baseUrl()).requestFactory(BoundedHttpRequests.factory()).build();
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
                        verify(json, reply.getHeaders().getFirst("X-Signature"));
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

    public GatewayPaymentStatus lookup(OrderDetails order) {
        var fields = mapper.createObjectNode().put("merchantId", order.merchantId()).put("txnRef", order.txnRef());
        try {
            var result = lookupClient.get().uri("/api/gateway/payments/{txnRef}", order.txnRef())
                    .header("X-Merchant-Id", order.merchantId()).header("X-Signature", hmac.sign(fields, properties.secretKey()))
                    .exchange((request, reply) -> {
                        if (reply.getStatusCode().is5xxServerError()) throw unavailable();
                        try {
                            byte[] bytes = reply.getBody().readNBytes(16385);
                            if (bytes.length > 16384) throw invalidResponse();
                            JsonNode json = mapper.readTree(bytes);
                            var signatures = reply.getHeaders().get("X-Signature");
                            if (signatures == null || signatures.size() != 1) throw invalidResponse();
                            verify(json, signatures.getFirst());
                            if (reply.getStatusCode().value() == 404 && "TXN_NOT_FOUND".equals(json.path("errorCode").asText())) {
                                throw new ApiException(HttpStatus.NOT_FOUND, "TXN_NOT_FOUND", "Payment transaction not found");
                            }
                            if (reply.getStatusCode().value() != 200) throw invalidResponse();
                            var names = json.fieldNames();
                            var allowed = Set.of("txnRef", "orderId", "amount", "status", "bankRef", "createdAt", "paidAt");
                            while (names.hasNext()) if (!allowed.contains(names.next())) throw invalidResponse();
                            return mapper.treeToValue(json, GatewayPaymentStatus.class);
                        } catch (java.net.SocketTimeoutException ex) { throw unavailable(); }
                        catch (IOException | IllegalArgumentException ex) { throw invalidResponse(); }
                    });
            if (result == null || !order.txnRef().equals(result.txnRef()) || !order.orderId().equals(result.orderId())
                    || result.amount() == null || result.amount() != order.amount() || result.createdAt() == null
                    || result.status() == null || !Set.of("PENDING", "PROCESSING", "SUCCESS", "FAILED", "EXPIRED", "REFUNDED").contains(result.status())) {
                throw invalidResponse();
            }
            if ("SUCCESS".equals(result.status()) || "REFUNDED".equals(result.status())) {
                if (result.bankRef() == null || result.bankRef().isBlank() || result.bankRef().length() > 32
                        || result.paidAt() == null || result.paidAt().isBefore(result.createdAt())) throw invalidResponse();
            } else if (result.bankRef() != null || result.paidAt() != null) throw invalidResponse();
            return result;
        } catch (RestClientException ex) { throw unavailable(); }
    }
    public GatewayReconciliation reconciliation(String merchantId, BusinessDay day) {
        var fields = mapper.createObjectNode().put("merchantId", merchantId).put("date", day.date().toString());
        try {
            return lookupClient.get().uri(builder -> builder.path("/api/gateway/reconciliation")
                            .queryParam("merchantId", merchantId).queryParam("date", day.date()).build())
                    .header("X-Merchant-Id", merchantId).header("X-Signature", hmac.sign(fields, properties.secretKey()))
                    .exchange((request, reply) -> {
                        if (reply.getStatusCode().is5xxServerError()) throw unavailable();
                        try {
                            byte[] bytes = reply.getBody().readNBytes(4 * 1024 * 1024 + 1);
                            if (bytes.length > 4 * 1024 * 1024) throw invalidResponse();
                            var json = mapper.readTree(bytes);
                            var signatures = reply.getHeaders().get("X-Signature");
                            if (signatures == null || signatures.size() != 1) throw invalidResponse();
                            verify(json, signatures.getFirst());
                            if (reply.getStatusCode().value() != 200) throw invalidResponse();
                            return GatewayReconciliation.read(json, mapper, merchantId, day);
                        } catch (java.net.SocketTimeoutException ex) { throw unavailable(); }
                        catch (IOException | IllegalArgumentException | ArithmeticException ex) { throw invalidResponse(); }
                    });
        } catch (RestClientException ex) { throw unavailable(); }
    }

    private void verify(JsonNode json, String signature) {
        try {
            if (json == null || !json.isObject() || !hmac.verify(json, properties.secretKey(), signature)) throw invalidResponse();
        } catch (IllegalArgumentException ex) { throw invalidResponse(); }
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE", "Gateway lookup is unavailable; order status is unchanged");
    }

    private static ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_GATEWAY_RESPONSE",
                "Gateway response could not be authenticated or does not match the order");
    }
}

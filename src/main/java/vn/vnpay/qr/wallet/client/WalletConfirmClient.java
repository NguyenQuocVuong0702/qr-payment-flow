package vn.vnpay.qr.wallet.client;
import java.io.IOException;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.wallet.api.WalletPaymentRequest;
import vn.vnpay.qr.wallet.config.WalletGatewayProperties;
import vn.vnpay.qr.wallet.config.WalletPaymentProperties;

@Component
public class WalletConfirmClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;
    private final WalletPaymentProperties config;
    public WalletConfirmClient(RestClient.Builder builder, WalletGatewayProperties gateway,
            WalletPaymentProperties config, ObjectMapper mapper, HmacSigner hmac) {
        client = builder.clone().baseUrl(gateway.baseUrl()).build();
        this.config = config; this.mapper = mapper; this.hmac = hmac;
    }
    public WalletPaymentResult confirm(WalletPaymentRequest request, String payerAccount) {
        var body = mapper.createObjectNode().put("payerAccount", payerAccount).put("amount", request.amount())
                .put("requestId", request.requestId());
        var fields = body.deepCopy().put("txnRef", request.txnRef());
        try {
            var result = client.post().uri("/api/gateway/payments/{txnRef}/confirm", request.txnRef())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("X-Wallet-Signature", hmac.sign(fields, config.secret())).body(body)
                    .exchange((outgoing, reply) -> {
                        if (reply.getStatusCode().is5xxServerError()) throw unknown();
                        try {
                            var json = mapper.readTree(reply.getBody());
                            if (json == null || !json.isObject() || !hmac.verify(json, config.secret(),
                                    reply.getHeaders().getFirst("X-Signature"))) throw unknown();
                            if (reply.getStatusCode().value() != 200) {
                                var codes = Map.of("TXN_NOT_FOUND", 404, "TXN_NOT_PAYABLE", 422,
                                        "TXN_EXPIRED", 422, "AMOUNT_MISMATCH", 422, "ACCOUNT_NOT_FOUND", 404,
                                        "IDEMPOTENCY_CONFLICT", 409, "INVALID_TRANSFER", 422);
                                String code = json.path("errorCode").asText();
                                if (codes.containsKey(code) && codes.get(code) == reply.getStatusCode().value()) {
                                    throw new ApiException(HttpStatus.valueOf(reply.getStatusCode().value()), code, "Gateway rejected payment");
                                }
                                throw unknown();
                            }
                            return mapper.treeToValue(json, WalletPaymentResult.class);
                        } catch (IOException | IllegalArgumentException ex) { throw unknown(); }
                    });
            if (result == null || !request.txnRef().equals(result.txnRef()) || !request.amount().equals(result.amount())
                    || !("SUCCESS".equals(result.status()) || "FAILED".equals(result.status()))
                    || "SUCCESS".equals(result.status()) && (result.bankRef() == null || result.paidAt() == null || result.errorCode() != null)
                    || "FAILED".equals(result.status()) && (result.errorCode() == null || result.bankRef() != null || result.paidAt() != null)) throw unknown();
            return result;
        } catch (RestClientException ex) { throw unknown(); }
    }
    private static ApiException unknown() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_RESULT_UNKNOWN",
                "Payment result is unknown; retry with the same requestId, txnRef and amount");
    }
}

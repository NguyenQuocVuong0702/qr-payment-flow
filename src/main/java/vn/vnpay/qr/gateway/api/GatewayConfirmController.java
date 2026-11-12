package vn.vnpay.qr.gateway.api;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.config.WalletConfirmProperties;
import vn.vnpay.qr.gateway.service.GatewayConfirmationService;

@RestController
public class GatewayConfirmController {
    private final GatewayConfirmationService payments;
    private final WalletConfirmProperties config;
    private final HmacSigner hmac;
    private final ObjectMapper mapper;
    public GatewayConfirmController(GatewayConfirmationService payments, WalletConfirmProperties config, HmacSigner hmac, ObjectMapper mapper) {
        this.payments = payments; this.config = config; this.hmac = hmac; this.mapper = mapper;
    }
    @PostMapping("/api/gateway/payments/{txnRef}/confirm")
    public ResponseEntity<JsonNode> confirm(@PathVariable String txnRef, @RequestHeader HttpHeaders headers,
            @Valid @RequestBody ConfirmPaymentRequest request) throws com.fasterxml.jackson.core.JsonProcessingException {
        var values = headers.get("X-Wallet-Signature");
        var fields = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(request);
        fields.put("txnRef", txnRef);
        if (headers.containsKey(HttpHeaders.AUTHORIZATION) || headers.containsKey("X-Merchant-Id") || headers.containsKey("X-Signature")
                || values == null || values.size() != 1 || !hmac.verify(fields, config.secret(), values.getFirst())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WALLET_CREDENTIALS", "Valid Wallet payment signature required");
        }
        LookupResponseSigningAdvice.signWith(config.secret());
        if (!txnRef.matches("TX[0-9]{8}")) throw new ApiException(HttpStatus.NOT_FOUND, "TXN_NOT_FOUND", "Payment transaction not found");
        var result = payments.confirm(txnRef, request);
        return ResponseEntity.status(result.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(mapper.readTree(result.responseBody()));
    }
}

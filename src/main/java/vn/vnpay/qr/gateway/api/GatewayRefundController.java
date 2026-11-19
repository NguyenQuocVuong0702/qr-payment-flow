package vn.vnpay.qr.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.service.GatewayRefundService;
import vn.vnpay.qr.gateway.service.PaymentLookupAccess;

@RestController
public class GatewayRefundController {
    private final PaymentLookupAccess access;
    private final GatewayRefundService refunds;
    private final ObjectMapper mapper;

    public GatewayRefundController(PaymentLookupAccess access, GatewayRefundService refunds, ObjectMapper mapper) {
        this.access = access;
        this.refunds = refunds;
        this.mapper = mapper;
    }

    @PostMapping("/api/gateway/payments/{txnRef}/refund")
    public RefundResult refund(@PathVariable String txnRef, @RequestHeader HttpHeaders headers,
            @Valid @RequestBody RefundRequest request) {
        if (!txnRef.matches("TX[0-9]{8}")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TXN_NOT_FOUND", "Payment transaction not found");
        }
        var fields = mapper.createObjectNode().put("action", "refund").put("merchantId", headers.getFirst("X-Merchant-Id"))
                .put("txnRef", txnRef).put("amount", request.amount()).put("requestId", request.requestId());
        var caller = access.authenticateMerchant(headers, fields);
        LookupResponseSigningAdvice.signWith(caller.signingKey());
        return refunds.refund(caller.merchantId(), txnRef, request);
    }
}

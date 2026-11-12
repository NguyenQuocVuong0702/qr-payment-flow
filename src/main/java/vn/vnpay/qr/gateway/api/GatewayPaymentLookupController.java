package vn.vnpay.qr.gateway.api;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpay.qr.gateway.service.PaymentLookupAccess;
import vn.vnpay.qr.gateway.service.PaymentLookupService;

@RestController
public class GatewayPaymentLookupController {
    private final PaymentLookupAccess access;
    private final PaymentLookupService payments;

    public GatewayPaymentLookupController(PaymentLookupAccess access, PaymentLookupService payments) {
        this.access = access;
        this.payments = payments;
    }

    @GetMapping("/api/gateway/payments/{txnRef}")
    public Object get(@PathVariable String txnRef, @RequestHeader HttpHeaders headers) {
        var caller = access.authenticate(headers, txnRef);
        LookupResponseSigningAdvice.signWith(caller.signingKey());
        return payments.lookup(txnRef, caller);
    }
}

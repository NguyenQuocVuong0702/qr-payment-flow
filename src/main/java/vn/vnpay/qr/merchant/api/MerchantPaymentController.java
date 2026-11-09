package vn.vnpay.qr.merchant.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.merchant.client.GatewayPayment;
import vn.vnpay.qr.merchant.service.MerchantPaymentService;

@RestController
@RequestMapping("/api/merchant/orders/{orderId}/payments")
public class MerchantPaymentController {
    private final MerchantPaymentService payments;

    public MerchantPaymentController(MerchantPaymentService payments) { this.payments = payments; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayPayment start(@PathVariable String orderId, @Valid @RequestBody StartPaymentRequest request) {
        return payments.start(orderId, request.expireMinutes());
    }
}
